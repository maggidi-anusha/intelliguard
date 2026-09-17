import logging
import time

import requests

import config
from client import BackendClient
from security_events import active_security_events, build_event_payload
from telemetry_generator import generate_logs_for_service, generate_metrics_for_service, load_schedule
from topology import register_services
from tracing import build_tracers, simulate_call_chain

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("simulator")


def safe_post(client, path, body, description):
    try:
        resp = client.post(path, body)
        if resp.status_code >= 400:
            log.warning("%s failed: %s %s", description, resp.status_code, resp.text)
    except requests.RequestException as exc:
        log.warning("%s failed: %s", description, exc)


def run_tick(client, services, schedule, elapsed_minutes):
    for name, service_id in services.items():
        metrics = generate_metrics_for_service(name, elapsed_minutes, schedule)
        for metric_type, value in metrics.items():
            safe_post(
                client,
                f"/api/services/{service_id}/metrics",
                {"metricType": metric_type, "value": value},
                f"metric {metric_type} for {name}",
            )

        for level, message in generate_logs_for_service(name, elapsed_minutes, schedule):
            safe_post(
                client,
                f"/api/services/{service_id}/logs",
                {"level": level, "message": message},
                f"log for {name}",
            )

    for entry in active_security_events(schedule, elapsed_minutes):
        service_id = services.get(entry.get("service"))
        safe_post(
            client,
            "/api/security-events",
            build_event_payload(entry, service_id),
            f"security event {entry['event_type']}",
        )


def wait_for_backend(client, timeout_seconds=120):
    # depends_on only orders container starts, not readiness - Spring Boot can take several
    # seconds after the container starts before it's actually accepting connections.
    log.info("Waiting for backend-core to become ready...")
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            resp = client.session.get(f"{client.base_url}/api/health", timeout=5)
            if resp.status_code == 200:
                log.info("backend-core is ready")
                return
        except requests.RequestException:
            pass
        time.sleep(2)
    raise RuntimeError("backend-core did not become ready in time")


def main():
    client = BackendClient()
    wait_for_backend(client)
    client.ensure_admin_registered()

    services = register_services(client)
    log.info("Topology ready: %s", services)

    schedule = load_schedule(config.SCENARIO_FILE)
    tracers = build_tracers(config.OTLP_ENDPOINT)

    start = time.monotonic()
    tick = 0

    while True:
        elapsed_minutes = (time.monotonic() - start) / 60.0

        run_tick(client, services, schedule, elapsed_minutes)
        simulate_call_chain(tracers, elapsed_minutes, schedule)

        tick += 1
        log.info("tick %d complete (elapsed %.2f min)", tick, elapsed_minutes)
        time.sleep(config.TICK_SECONDS)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log.info("Simulator stopped")
