import json

import pytest

from conftest import SCHEDULE, SERVICES, T0, run_engine
from telemetry_generator import ANOMALY_METRIC_MAP, ANOMALY_TARGET_RANGES


def test_normal_mode_produces_no_labels_injections_or_security_events(make_engine):
    metrics, security, labels = run_engine(make_engine(mode="normal"), 12 * 60)

    assert labels == []
    assert security == []
    assert not any(r.injected for r in metrics)


def test_unknown_mode_is_rejected(make_engine):
    with pytest.raises(ValueError):
        make_engine(mode="chaos")


def _legacy_active(entry, elapsed_minutes):
    # The original simulator's rule (telemetry_generator._active_entries before Phase 4.0).
    return entry["start_minute"] <= elapsed_minutes < entry["start_minute"] + entry["duration_minutes"]


def test_demo_mode_matches_the_original_schedule_windows(make_engine):
    with open(SCHEDULE) as f:
        schedule = json.load(f)
    metrics, security, labels = run_engine(make_engine(mode="demo"), 20)

    for entry in schedule["metric_anomalies"]:
        metric = ANOMALY_METRIC_MAP[entry["anomaly_type"]]
        rows = [r for r in metrics if r.service_name == entry["service"] and r.metric_type == metric]
        expected = [r.timestamp for r in rows if _legacy_active(entry, (r.timestamp - T0).total_seconds() / 60)]
        assert [r.timestamp for r in rows if r.injected] == expected

        low, high = ANOMALY_TARGET_RANGES[metric][entry["magnitude"]]
        assert all(low <= r.value <= high for r in rows if r.injected)

        [label] = [l for l in labels if l["signal_type"] == "METRIC" and l["metric_type"] == metric
                   and l["service_id"] == SERVICES[entry["service"]]]
        assert (label["start_time"], label["end_time"]) == (expected[0], expected[-1])
        assert (label["scenario_type"], label["difficulty"]) == ("SPIKE", "EASY")
        assert label["params"]["anomaly_type"] == entry["anomaly_type"]

    for entry in schedule["security_events"]:
        expected = [ts for ts in sorted({r.timestamp for r in metrics})
                    if _legacy_active(entry, (ts - T0).total_seconds() / 60)]
        emitted = [r.timestamp for r in security if r.event_type == entry["event_type"]]
        assert emitted == expected  # one event per tick, exactly in the original window
        [label] = [l for l in labels if l["signal_type"] == "SECURITY" and l["params"]["event_type"] == entry["event_type"]]
        assert (label["start_time"], label["end_time"]) == (expected[0], expected[-1])
        assert label["service_id"] == SERVICES[entry["service"]]
