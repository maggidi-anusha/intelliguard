import logging
import signal
import time
from datetime import datetime, timezone

import requests

import config
from client import BackendClient
from engine import SCENARIO_MODES, SimulationEngine, format_ts, make_run_id, resolve_seed
from topology import register_services
from tracing import build_tracers, simulate_call_chain

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("simulator")


class _Shutdown(Exception):
    pass


def _on_sigterm(signum, frame):
    # `docker compose stop/down` sends SIGTERM. Turn it into an exception so the main loop's
    # `finally` can flush partial episode labels; ignore repeats so the flush itself can finish.
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    raise _Shutdown()


def safe_post(client, path, body, description):
    try:
        resp = client.post(path, body)
        if resp.status_code >= 400:
            log.warning("%s failed: %s %s", description, resp.status_code, resp.text)
    except requests.RequestException as exc:
        log.warning("%s failed: %s", description, exc)


def post_tick(client, result):
    # Every row carries its tick's timestamp, so stored rows and ground-truth labels share
    # exactly the same timestamps (rather than each POST's server receipt time).
    for row in result.metrics:
        safe_post(
            client,
            f"/api/services/{row.service_id}/metrics",
            {"metricType": row.metric_type, "value": row.value, "timestamp": format_ts(row.timestamp)},
            f"metric {row.metric_type} for {row.service_name}",
        )
    for row in result.logs:
        safe_post(
            client,
            f"/api/services/{row.service_id}/logs",
            {"level": row.level, "message": row.message, "timestamp": format_ts(row.timestamp)},
            f"log for {row.service_name}",
        )
    for row in result.security:
        safe_post(
            client,
            "/api/security-events",
            {"serviceId": row.service_id, "eventType": row.event_type, "severity": row.severity,
             "details": row.details, "timestamp": format_ts(row.timestamp)},
            f"security event {row.event_type}",
        )


def post_labels(client, labels):
    # A failed label POST is logged and skipped - telemetry generation must keep going.
    for label in labels:
        safe_post(
            client,
            "/api/ground-truth",
            {
                "runId": label["run_id"],
                "seed": label["seed"],
                "serviceId": label["service_id"],
                "signalType": label["signal_type"],
                "metricType": label["metric_type"],
                "scenarioType": label["scenario_type"],
                "difficulty": label["difficulty"],
                "startTime": format_ts(label["start_time"]),
                "endTime": format_ts(label["end_time"]),
                "params": label["params"],
            },
            f"ground-truth label {label['params']['episode_id']} ({label['signal_type']} {label['metric_type'] or ''})",
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
    signal.signal(signal.SIGTERM, _on_sigterm)

    seed, generated = resolve_seed(config.SEED)
    mode = config.SCENARIO_MODE
    if mode not in SCENARIO_MODES:
        raise SystemExit(f"SCENARIO_MODE must be one of {', '.join(SCENARIO_MODES)}, got {mode!r}")

    client = BackendClient()
    wait_for_backend(client)

    services = register_services(client)
    log.info("Topology ready: %s", services)

    run_start = datetime.now(timezone.utc)
    run_id = make_run_id("live", mode, run_start, seed)
    engine = SimulationEngine(seed, mode, run_start, services, run_id, schedule_path=config.SCENARIO_FILE)
    log.info("Run %s: SCENARIO_MODE=%s SIMULATOR_SEED=%d (%s)", run_id, mode, seed,
             "generated" if generated else "configured")

    tracers = build_tracers(config.OTLP_ENDPOINT)
    tick = 0
    try:
        while True:
            now = datetime.now(timezone.utc)
            result = engine.tick(now)

            post_tick(client, result)
            simulate_call_chain(tracers, engine, now)
            post_labels(client, result.labels)
            for label in result.labels:
                log.info("Ground-truth label posted: %s %s %s %s %s..%s", label["params"]["episode_id"],
                         label["signal_type"], label["metric_type"] or label["params"].get("event_type"),
                         label["scenario_type"], format_ts(label["start_time"]), format_ts(label["end_time"]))

            tick += 1
            log.info("tick %d complete (elapsed %.2f min)", tick, engine.elapsed_seconds(now) / 60.0)
            time.sleep(config.TICK_SECONDS)
    except (KeyboardInterrupt, _Shutdown):
        log.info("Simulator stopping")
    finally:
        partial = engine.flush(datetime.now(timezone.utc))
        if partial:
            log.info("Flushing %d label(s) for episode(s) still in progress", len(partial))
            post_labels(client, partial)


if __name__ == "__main__":
    main()
