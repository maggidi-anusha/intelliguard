"""Offline dataset generator - the live simulator's engine on simulated time, no backend.

    python -m simulator.offline --days 1 --seed 42 --mode continuous --out datasets/run1

Uses the exact same generator and scenario code as the live simulator (engine.py), stepping a
simulated clock in TICK_SECONDS increments without sleeping. Writes metrics.csv, logs.csv,
security_events.csv and labels.csv (labels.csv has the same shape as the ground_truth_events
table). The same arguments always produce byte-identical files.
"""
from __future__ import annotations

import argparse
import csv
import json
import logging
import os
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone

# The simulator modules use flat imports (they run as /app/*.py in the container); make that
# work when invoked as `python -m simulator.offline` from the repo root too.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from engine import SimulationEngine, format_ts, make_run_id, resolve_seed  # noqa: E402
from topology import SEED_SERVICES  # noqa: E402

log = logging.getLogger("simulator.offline")

TICK_SECONDS = 5
# A fixed default start keeps output reproducible (a "now" default would change every run).
DEFAULT_START = "2026-01-01T00:00:00Z"
DEFAULT_MINUTES = 6 * 60
METRIC_ROWS_PER_DAY = (86400 // TICK_SECONDS) * len(SEED_SERVICES) * 6  # 829,440

LABEL_COLUMNS = ["id", "run_id", "seed", "service_id", "signal_type", "metric_type", "scenario_type",
                 "difficulty", "start_time", "end_time", "params", "created_at"]


def parse_ts(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def _json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"))


def generate(out_dir, seed, mode, duration_minutes, start=None, tick_seconds=TICK_SECONDS):
    """Write the four CSVs to out_dir and return a summary dict."""
    if mode not in ("continuous", "normal"):
        raise ValueError("offline mode must be 'continuous' or 'normal'")
    start = start or parse_ts(DEFAULT_START)
    services = {s["name"]: i for i, s in enumerate(SEED_SERVICES, 1)}
    run_id = make_run_id("offline", mode, start, seed)
    engine = SimulationEngine(seed, mode, start, services, run_id)
    n_ticks = int(duration_minutes * 60 // tick_seconds)

    os.makedirs(out_dir, exist_ok=True)
    files = {name: open(os.path.join(out_dir, f"{name}.csv"), "w", newline="")
             for name in ("metrics", "logs", "security_events", "labels")}
    try:
        writers = {name: csv.writer(f, lineterminator="\n") for name, f in files.items()}
        writers["metrics"].writerow(["timestamp", "service_id", "service_name", "metric_type", "value"])
        writers["logs"].writerow(["timestamp", "service_id", "service_name", "level", "message"])
        writers["security_events"].writerow(["timestamp", "service_id", "service_name", "event_type", "severity", "details"])
        writers["labels"].writerow(LABEL_COLUMNS)

        counts = Counter()
        episodes = {}
        label_id = 0

        def write_labels(labels):
            nonlocal label_id
            for label in labels:
                label_id += 1
                writers["labels"].writerow([
                    label_id, label["run_id"], label["seed"], label["service_id"], label["signal_type"],
                    label["metric_type"] or "", label["scenario_type"], label["difficulty"],
                    format_ts(label["start_time"]), format_ts(label["end_time"]),
                    _json(label["params"]), format_ts(label["created_at"]),
                ])
                episodes[label["params"]["episode_id"]] = (label["scenario_type"], label["difficulty"])
                counts["labels_" + label["signal_type"].lower()] += 1

        for i in range(n_ticks):
            ts = start + timedelta(seconds=i * tick_seconds)
            result = engine.tick(ts)
            for row in result.metrics:
                writers["metrics"].writerow([format_ts(row.timestamp), row.service_id, row.service_name,
                                             row.metric_type, row.value])
                counts["metric_rows"] += 1
                counts["anomalous_metric_rows"] += row.injected
            for row in result.logs:
                writers["logs"].writerow([format_ts(row.timestamp), row.service_id, row.service_name,
                                          row.level, row.message])
                counts["log_rows"] += 1
            for row in result.security:
                writers["security_events"].writerow([format_ts(row.timestamp), row.service_id, row.service_name,
                                                     row.event_type, row.severity, _json(row.details)])
                counts["security_rows"] += 1
            write_labels(result.labels)

        # Close out an episode still running when the simulated window ends (marked partial).
        write_labels(engine.flush(start + timedelta(seconds=n_ticks * tick_seconds)))
    finally:
        for f in files.values():
            f.close()

    return {
        "run_id": run_id,
        "seed": seed,
        "mode": mode,
        "start": format_ts(start),
        "ticks": n_ticks,
        "episodes": len(episodes),
        "episodes_by_type": {f"{s}/{d}": n for (s, d), n in sorted(Counter(episodes.values()).items())},
        **dict(counts),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m simulator.offline", description=__doc__.split("\n\n")[0])
    parser.add_argument("--days", type=int, default=0, help="simulated days (combined with --minutes)")
    parser.add_argument("--minutes", type=int, default=0, help="simulated minutes (combined with --days)")
    parser.add_argument("--seed", type=int, default=None, help="SIMULATOR_SEED; generated and printed if omitted")
    parser.add_argument("--mode", choices=["continuous", "normal"], default="continuous")
    parser.add_argument("--start", default=DEFAULT_START, help=f"simulated start time, ISO-8601 (default {DEFAULT_START})")
    parser.add_argument("--out", default=None, help="output directory (default datasets/<run_id>)")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    duration = args.days * 1440 + args.minutes or DEFAULT_MINUTES
    seed, generated = resolve_seed(args.seed)
    start = parse_ts(args.start)
    out_dir = args.out or os.path.join("datasets", make_run_id("offline", args.mode, start, seed))

    est_rows = int(METRIC_ROWS_PER_DAY * duration / 1440)
    if duration > 1440:
        log.warning("Large run: %.1f simulated days -> ~%s metric rows (~%d MB of CSV)",
                    duration / 1440, f"{est_rows:,}", est_rows * 70 // 1_000_000)
    log.info("seed=%d (%s) mode=%s duration=%d min -> %s", seed, "generated" if generated else "given",
             args.mode, duration, out_dir)

    summary = generate(out_dir, seed, args.mode, duration, start)
    print(json.dumps(summary, indent=2, default=str))


if __name__ == "__main__":
    main()
