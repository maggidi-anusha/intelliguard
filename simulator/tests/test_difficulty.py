import statistics

import pytest

from conftest import run_engine
from episodes import CORRELATED_PAIRS, SCENARIO_TYPES, build_episode
from telemetry_generator import METRIC_BASELINES, METRIC_TYPES, baseline_stats, rng_stream

DURATION_S = 15 * 60  # 180 ticks - enough for stable sample statistics


def _injected_values(make_engine, scenario, difficulty, metrics, service="order-service", seed=3):
    episode = build_episode(rng_stream(seed, "test"), "ep-t", service, scenario, difficulty, 60, DURATION_S, metrics)
    engine = make_engine(seed=seed, plan=[episode])
    rows, _, _ = run_engine(engine, (60 + DURATION_S) / 60)
    values = {m: [r.value for r in rows if r.injected and r.metric_type == m] for m in metrics}
    mean, std, _, _ = baseline_stats(metrics[0], engine.jitter(service))
    return episode, values, mean, std


def _metric_sets(scenario):
    return [list(p) for p in CORRELATED_PAIRS] if scenario == "CORRELATED" else [[m] for m in METRIC_TYPES]


@pytest.mark.parametrize("scenario", SCENARIO_TYPES)
def test_easy_values_never_overlap_the_normal_range(make_engine, scenario):
    for metrics in _metric_sets(scenario):
        _, values, _, _ = _injected_values(make_engine, scenario, "EASY", metrics)
        for metric, vals in values.items():
            normal_max = METRIC_BASELINES[metric][3]
            assert len(vals) == DURATION_S // 5
            assert min(vals) > normal_max, (scenario, metric, min(vals))


@pytest.mark.parametrize("scenario", SCENARIO_TYPES)
def test_subtle_values_stay_inside_the_normal_range(make_engine, scenario):
    for metrics in _metric_sets(scenario):
        _, values, _, _ = _injected_values(make_engine, scenario, "SUBTLE", metrics)
        for metric, vals in values.items():
            _, _, lo, hi = METRIC_BASELINES[metric]
            assert all(lo <= v <= hi for v in vals), (scenario, metric)


def _shift_in_sigma(vals, mean, std):
    return (statistics.mean(vals) - mean) / std


@pytest.mark.parametrize("scenario", ["SPIKE", "LEVEL_SHIFT"])
def test_subtle_shift_is_about_k_sigma_and_overlaps_normal_data(make_engine, scenario):
    # CPU has no clamping near its normal range, so sample statistics reflect the design.
    episode, values, mean, std = _injected_values(make_engine, scenario, "SUBTLE", ["CPU"])
    k = episode.metric_params["CPU"]["k_sigma"]
    vals = values["CPU"]

    assert 1.5 <= k <= 3.0
    assert abs(_shift_in_sigma(vals, mean, std) - k) < 0.5
    assert sum(v <= mean + 3 * std for v in vals) / len(vals) >= 0.25  # overlaps normal data


def test_subtle_drift_starts_near_normal_and_ends_about_k_sigma_up(make_engine):
    episode, values, mean, std = _injected_values(make_engine, "DRIFT", "SUBTLE", ["CPU"])
    k = episode.metric_params["CPU"]["k_sigma"]
    vals = values["CPU"]
    fifth = len(vals) // 5

    assert abs(_shift_in_sigma(vals[:fifth], mean, std)) < 0.75 * k
    assert abs(_shift_in_sigma(vals[-fifth:], mean, std) - 0.9 * k) < 0.75


def test_subtle_variance_inflates_spread_without_moving_the_mean(make_engine):
    episode, values, mean, std = _injected_values(make_engine, "VARIANCE", "SUBTLE", ["CPU"])
    factor = episode.metric_params["CPU"]["variance_factor"]
    vals = values["CPU"]

    assert abs(statistics.pstdev(vals) / std - factor) < 0.5
    assert abs(_shift_in_sigma(vals, mean, std)) < 0.5


def test_subtle_correlated_metrics_move_together(make_engine):
    _, values, _, _ = _injected_values(make_engine, "CORRELATED", "SUBTLE", ["CPU", "LATENCY"])
    a, b = values["CPU"], values["LATENCY"]
    ma, mb = statistics.mean(a), statistics.mean(b)
    cov = sum((x - ma) * (y - mb) for x, y in zip(a, b)) / len(a)
    corr = cov / (statistics.pstdev(a) * statistics.pstdev(b))

    assert corr > 0.3
