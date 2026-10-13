#!/usr/bin/env python3
"""End-to-end smoke test for a running IntelliGuard stack (Python standard library only).

    python scripts/smoke_test.py --base-url http://localhost:8081 --admin-user simulator-admin
    (admin password from --admin-password or the SMOKE_ADMIN_PASSWORD environment variable)

Prints a pass/fail table and exits non-zero if any check fails. The copilot check runs only when an
incident exists; otherwise it is reported as SKIPPED (not PASS). It registers one throwaway VIEWER
account (smoke-viewer-<random>) through public registration to check the read-only role.
"""
import argparse
import json
import os
import secrets
import sys
import urllib.error
import urllib.request

PASS, FAIL, SKIPPED = "PASS", "FAIL", "SKIPPED"


class Client:
    def __init__(self, base_url):
        self.base = base_url.rstrip("/")

    def call(self, method, path, body=None, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read().decode()
                return r.status, parse(raw)
        except urllib.error.HTTPError as e:
            return e.code, parse(e.read().decode())
        except (urllib.error.URLError, OSError) as e:
            return 0, str(e)


def parse(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return raw


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default=os.environ.get("SMOKE_BASE_URL", "http://localhost"))
    p.add_argument("--admin-user", default=os.environ.get("SMOKE_ADMIN_USER", "simulator-admin"))
    p.add_argument("--admin-password", default=os.environ.get("SMOKE_ADMIN_PASSWORD"))
    args = p.parse_args()
    if not args.admin_password:
        p.error("admin password required (--admin-password or SMOKE_ADMIN_PASSWORD)")

    c = Client(args.base_url)
    results = []

    def check(name, ok, detail, skipped=False):
        results.append((name, SKIPPED if skipped else (PASS if ok else FAIL), detail))
        return ok

    status, body = c.call("GET", "/api/health")
    check("health", status == 200, f"GET /api/health -> {status}")

    status, body = c.call("POST", "/api/auth/login", {"username": args.admin_user, "password": args.admin_password})
    admin = body.get("accessToken") if status == 200 and isinstance(body, dict) else None
    check("admin login", admin is not None, f"POST /api/auth/login -> {status}" + (f", role {body.get('role')}" if admin else ""))

    reads = [("services list non-empty", "/api/services", lambda b: isinstance(b, list) and len(b) > 0, lambda b: f"{len(b)} services"),
             ("dashboard summary", "/api/dashboard/summary", lambda b: isinstance(b, dict) and "totalServices" in b,
              lambda b: f"{b.get('totalServices')} services, {b.get('healthyServices')} healthy"),
             ("risk current", "/api/risk/current", lambda b: isinstance(b, list), lambda b: f"{len(b)} services scored or pending"),
             ("anomalies list", "/api/anomalies", lambda b: isinstance(b, list), lambda b: f"{len(b)} anomalies"),
             ("incidents list", "/api/incidents", lambda b: isinstance(b, list), lambda b: f"{len(b)} incidents")]
    incidents = []
    for name, path, valid, describe in reads:
        status, body = c.call("GET", path, token=admin)
        ok = status == 200 and valid(body)
        check(name, ok, f"GET {path} -> {status}" + (f", {describe(body)}" if ok else ""))
        if path == "/api/incidents" and ok:
            incidents = body

    viewer_name = "smoke-viewer-" + secrets.token_hex(4)
    viewer_pw = secrets.token_urlsafe(16)
    status, body = c.call("POST", "/api/auth/register", {"username": viewer_name, "password": viewer_pw,
                                                         "email": f"{viewer_name}@example.invalid"})
    registered = status == 201 and isinstance(body, dict) and body.get("role") == "VIEWER"
    check("public registration creates a VIEWER", registered, f"POST /api/auth/register -> {status}"
          + (f", role {body.get('role')}" if isinstance(body, dict) else ""))
    viewer = None
    if registered:
        status, body = c.call("POST", "/api/auth/login", {"username": viewer_name, "password": viewer_pw})
        viewer = body.get("accessToken") if status == 200 and isinstance(body, dict) else None
    status, _ = c.call("GET", "/api/services", token=viewer)
    check("VIEWER can read", viewer is not None and status == 200, f"GET /api/services as VIEWER -> {status}")
    status, _ = c.call("POST", "/api/services", {"name": "smoke-test-service", "type": "MICROSERVICE",
                                                 "criticality": "LOW"}, token=viewer)
    check("VIEWER cannot write", viewer is not None and status == 403, f"POST /api/services as VIEWER -> {status} (expect 403)")
    status, _ = c.call("GET", "/api/ground-truth", token=viewer)
    check("VIEWER cannot read ground truth", viewer is not None and status == 403,
          f"GET /api/ground-truth as VIEWER -> {status} (expect 403)")

    status, _ = c.call("GET", "/api/services")
    check("no token is rejected", status == 401, f"GET /api/services without a token -> {status} (expect 401)")

    if incidents:
        incident = incidents[0]
        status, body = c.call("POST", f"/api/incidents/{incident['id']}/copilot/ask",
                              {"question": "What happened?"}, token=viewer or admin)
        ok = status == 200 and isinstance(body, dict) and bool(body.get("answer")) and body.get("mode") == "extractive"
        check("copilot ask", ok, f"POST /api/incidents/{incident['id']}/copilot/ask -> {status}"
              + (f", intent {body.get('intent')}, refused {body.get('refused')}, {len(body.get('citations', []))} citation(s)"
                 if isinstance(body, dict) else ""))
    else:
        check("copilot ask", False, "no incident exists yet", skipped=True)

    width = max(len(r[0]) for r in results)
    print(f"Smoke test against {c.base}\n")
    print(f"{'Check':<{width}}  {'Result':<7}  Detail")
    print(f"{'-' * width}  {'-' * 7}  {'-' * 40}")
    for name, result, detail in results:
        print(f"{name:<{width}}  {result:<7}  {detail}")
    failed = [r for r in results if r[1] == FAIL]
    counts = {k: sum(r[1] == k for r in results) for k in (PASS, FAIL, SKIPPED)}
    print(f"\n{counts[PASS]} passed, {counts[FAIL]} failed, {counts[SKIPPED]} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
