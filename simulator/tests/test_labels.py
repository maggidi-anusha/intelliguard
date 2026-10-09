from collections import defaultdict
from datetime import timedelta

from conftest import T0, run_engine
from episodes import build_episode
from telemetry_generator import rng_stream


def _assert_labels_match_injected_rows(metrics, security, labels):
    injected = {(r.service_id, r.metric_type, r.timestamp) for r in metrics if r.injected}
    by_series = defaultdict(list)
    for r in metrics:
        by_series[(r.service_id, r.metric_type)].append(r)

    covered = set()
    for label in (l for l in labels if l["signal_type"] == "METRIC"):
        rows = [r for r in by_series[(label["service_id"], label["metric_type"])]
                if label["start_time"] <= r.timestamp <= label["end_time"]]
        # Every row inside the label's window was injected, and the window is exactly the
        # first..last injected tick.
        assert rows and all(r.injected for r in rows), label
        assert rows[0].timestamp == label["start_time"] and rows[-1].timestamp == label["end_time"]
        assert len(rows) == label["params"]["injected_ticks"]
        covered.update((r.service_id, r.metric_type, r.timestamp) for r in rows)
    # ...and no injected row is missing a label.
    assert covered == injected

    sec_covered = set()
    for label in (l for l in labels if l["signal_type"] == "SECURITY"):
        assert label["metric_type"] is None
        rows = [r for r in security if r.service_id == label["service_id"]
                and r.event_type == label["params"]["event_type"]
                and label["start_time"] <= r.timestamp <= label["end_time"]]
        assert len(rows) == label["params"]["event_count"] > 0
        assert rows[0].timestamp == label["start_time"] and rows[-1].timestamp == label["end_time"]
        sec_covered.update(id(r) for r in rows)
    assert sec_covered == {id(r) for r in security}


def test_every_label_matches_exactly_the_injected_rows(make_engine):
    metrics, security, labels = run_engine(make_engine(seed=99), 24 * 60)

    assert len({l["params"]["episode_id"] for l in labels}) >= 30
    assert security, "expected some episodes with attached security events"
    _assert_labels_match_injected_rows(metrics, security, labels)
    assert all(l["seed"] == 99 and l["run_id"] == "test-continuous-s99" for l in labels)


def test_security_labels_share_the_episode_of_their_metric_anomaly(make_engine):
    _, _, labels = run_engine(make_engine(seed=99), 24 * 60)
    metric_episodes = {l["params"]["episode_id"]: l for l in labels if l["signal_type"] == "METRIC"}

    for sec in (l for l in labels if l["signal_type"] == "SECURITY"):
        owner = metric_episodes[sec["params"]["episode_id"]]
        assert sec["service_id"] == owner["service_id"]
        assert (sec["scenario_type"], sec["difficulty"]) == (owner["scenario_type"], owner["difficulty"])


def test_flush_labels_an_in_progress_episode_as_partial(make_engine):
    episode = build_episode(rng_stream(0, "t"), "ep-x", "order-service", "LEVEL_SHIFT", "EASY", 60, 600, ["CPU"])
    engine = make_engine(plan=[episode])
    ticks = [T0 + timedelta(seconds=5 * i) for i in range(60)]  # stop at 5 min, mid-episode
    for ts in ticks:
        engine.tick(ts)

    [label] = engine.flush(ticks[-1])
    assert label["params"]["partial"] is True
    assert label["start_time"] == T0 + timedelta(seconds=60)
    assert label["end_time"] == ticks[-1]


def test_wall_clock_gap_cannot_shift_the_schedule(make_engine):
    # Simulate the host sleeping from minute 4 to minute 30: an episode entirely inside the gap
    # injects nothing (no label); one straddling it is labelled only with the ticks that ran.
    rng = rng_stream(0, "t")
    inside = build_episode(rng, "ep-in-gap", "auth-service", "SPIKE", "EASY", 10 * 60, 120, ["ERROR_RATE"])
    straddle = build_episode(rng, "ep-straddle", "order-db", "LEVEL_SHIFT", "EASY", 25 * 60, 15 * 60, ["LATENCY"])
    engine = make_engine(plan=[inside, straddle])

    ticks = [T0 + timedelta(seconds=5 * i) for i in range(48)]  # 0..4 min
    ticks += [T0 + timedelta(minutes=30, seconds=5 * i) for i in range(240)]  # 30..50 min
    metrics, security, labels = [], [], []
    for ts in ticks:
        result = engine.tick(ts)
        metrics.extend(result.metrics)
        labels.extend(result.labels)

    assert engine.skipped_episodes == ["ep-in-gap"]
    [label] = labels
    assert label["params"]["episode_id"] == "ep-straddle"
    assert label["start_time"] == T0 + timedelta(minutes=30)  # first tick that actually ran
    assert label["end_time"] == T0 + timedelta(minutes=39, seconds=55)
    _assert_labels_match_injected_rows(metrics, security, labels)
