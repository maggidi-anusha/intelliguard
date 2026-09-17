import logging
import time

import requests

import config

log = logging.getLogger("simulator.client")

# backend-core's JwtUtil issues 15-minute access tokens (see JwtUtil.ACCESS_EXPIRATION).
# The simulator runs far longer than that, so re-login a bit before expiry rather than
# discovering it via a wave of 401s.
TOKEN_REFRESH_MARGIN_SECONDS = 12 * 60


class BackendClient:
    def __init__(self, base_url=config.BACKEND_URL):
        self.base_url = base_url
        self.session = requests.Session()
        self.token = None
        self.token_acquired_at = 0.0

    def ensure_admin_registered(self):
        try:
            resp = self.session.post(
                f"{self.base_url}/api/auth/register",
                json={
                    "username": config.ADMIN_USERNAME,
                    "password": config.ADMIN_PASSWORD,
                    "email": config.ADMIN_EMAIL,
                    "role": "ADMIN",
                },
                timeout=10,
            )
            if resp.status_code == 201:
                log.info("Registered simulator admin account '%s'", config.ADMIN_USERNAME)
            elif resp.status_code == 409:
                log.info("Simulator admin account '%s' already exists", config.ADMIN_USERNAME)
            else:
                log.warning("Unexpected register response %s: %s", resp.status_code, resp.text)
        except requests.RequestException as exc:
            log.warning("Register request failed: %s", exc)

    def login(self):
        resp = self.session.post(
            f"{self.base_url}/api/auth/login",
            json={"username": config.ADMIN_USERNAME, "password": config.ADMIN_PASSWORD},
            timeout=10,
        )
        resp.raise_for_status()
        self.token = resp.json()["accessToken"]
        self.token_acquired_at = time.monotonic()
        log.info("Logged in as %s", config.ADMIN_USERNAME)

    def _headers(self):
        if self.token is None or time.monotonic() - self.token_acquired_at > TOKEN_REFRESH_MARGIN_SECONDS:
            self.login()
        return {"Authorization": f"Bearer {self.token}"}

    def _do(self, request_fn, path, **kwargs):
        resp = request_fn(f"{self.base_url}{path}", headers=self._headers(), timeout=10, **kwargs)
        if resp.status_code in (401, 403):
            # Could be a stale token rather than a real permissions problem - e.g. backend-core
            # restarted and its ephemeral JWT signing key (no JWT_SECRET set) rotated, so every
            # previously-issued token silently stops validating. One retry after a forced
            # re-login covers that case cheaply; a genuine permissions issue just fails again.
            self.login()
            resp = request_fn(f"{self.base_url}{path}", headers=self._headers(), timeout=10, **kwargs)
        return resp

    def get(self, path, **kwargs):
        return self._do(self.session.get, path, **kwargs)

    def post(self, path, json_body):
        return self._do(self.session.post, path, json=json_body)
