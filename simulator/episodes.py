"""Anomaly episodes: what gets injected, where, and when.

An episode is one injected anomaly on one service. It affects one metric, or two for
CORRELATED, and can carry an attached security-event component (FAILED_LOGIN / PORT_SCAN)
that belongs to the same episode. Episode times are seconds since the run started. They are
compared against each tick's own timestamp (wall clock live, simulated offline), never a
monotonic clock, so Mac sleep can't shift the schedule relative to the data.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Dict, Iterator, List, Optional

from telemetry_generator import (
    ANOMALY_METRIC_MAP,
    ANOMALY_TARGET_RANGES,
    METRIC_TYPES,
    baseline_stats,
    rng_stream,
)

SCENARIO_TYPES = ["SPIKE", "LEVEL_SHIFT", "DRIFT", "VARIANCE", "CORRELATED"]
DIFFICULTIES = ["EASY", "SUBTLE"]

# EASY values are drawn from these bands, which start strictly above each metric's normal
# clamp ceiling (METRIC_BASELINES max) - an EASY value can never be a plausible normal value.
EASY_BANDS = {
    "CPU": (70.0, 99.0),
    "MEMORY": (78.0, 98.0),
    "DISK": (78.0, 96.0),
    "NETWORK": (80.0, 300.0),
    "LATENCY": (250.0, 2000.0),
    "ERROR_RATE": (5.0, 45.0),
}

# SUBTLE shifts (in baseline standard deviations) and variance multipliers. These values
# overlap the normal range by design.
SUBTLE_SIGMA_RANGE = (1.5, 3.0)

DURATION_MINUTES = {
    "SPIKE": (1, 3),
    "LEVEL_SHIFT": (5, 15),
    "DRIFT": (10, 30),
    "VARIANCE": (5, 15),
    "CORRELATED": (3, 10),
}

# Quiet time between consecutive continuous-mode episodes.
GAP_MINUTES = (5, 20)

# Metric pairs that plausibly move together on one service.
CORRELATED_PAIRS = [
    ("CPU", "LATENCY"),
    ("ERROR_RATE", "LATENCY"),
    ("NETWORK", "CPU"),
    ("MEMORY", "DISK"),
]

# Which security events a continuous-mode episode on a given service may carry, and how often.
SECURITY_BY_SERVICE = {
    "auth-service": ("FAILED_LOGIN", "HIGH"),
    "user-db": ("PORT_SCAN", "MEDIUM"),
    "order-db": ("PORT_SCAN", "MEDIUM"),
    "inventory-db": ("PORT_SCAN", "MEDIUM"),
}
SECURITY_ATTACH_PROBABILITY = 0.5
SECURITY_MAX_WINDOW_SECONDS = 120
SECURITY_EMIT_PROBABILITY = {"EASY": 1.0, "SUBTLE": 0.35}


@dataclass
class SecurityComponent:
    event_type: str
    severity: str
    start_s: float
    end_s: float
    emit_probability: float

    def is_active(self, elapsed_s):
        return self.start_s <= elapsed_s < self.end_s


@dataclass
class Episode:
    episode_id: str
    service: str
    scenario_type: str
    difficulty: str
    start_s: float
    end_s: float
    # metric_type -> how its values are generated while the episode is active (see anomaly_value)
    metric_params: Dict[str, dict]
    security: Optional[SecurityComponent] = None
    # Extra, label-only context (e.g. the legacy demo anomaly_type/magnitude)
    label_params: dict = field(default_factory=dict)

    @property
    def primary_metric(self):
        return next(iter(self.metric_params))

    def is_active(self, elapsed_s):
        return self.start_s <= elapsed_s < self.end_s


# --- per-tick value generation ---------------------------------------------------------------

def correlated_factor(rng, difficulty):
    """One shared draw per tick that both metrics of a CORRELATED episode scale by."""
    return rng.uniform(0.0, 1.0) if difficulty == "EASY" else rng.uniform(0.5, 1.0)


def anomaly_value(rng, metric_type, params, jitter, progress, shared=None):
    """Value of `metric_type` on one tick of an active episode.

    progress: 0.0 at the episode's planned start, approaching 1.0 at its planned end.
    shared:   the tick's correlated_factor, for CORRELATED episodes.
    """
    kind = params["kind"]
    mean, std, lo, hi = baseline_stats(metric_type, jitter)
    band_lo, band_hi = EASY_BANDS[metric_type]

    if kind == "legacy_range":  # demo mode - identical to the original simulator
        value = rng.uniform(*params["range"])
    # EASY: always inside the EASY band, i.e. strictly above the normal clamp ceiling.
    elif kind == "easy_spike":
        value = rng.uniform(band_lo, band_hi)
    elif kind == "easy_level_shift":
        value = min(band_hi, max(band_lo, rng.gauss(params["level"], std)))
    elif kind == "easy_drift":
        target = band_lo + progress * (params["target"] - band_lo)
        value = min(band_hi, max(band_lo, target + rng.gauss(0.0, std * 0.5)))
    elif kind == "easy_variance":
        value = min(band_hi, max(band_lo, band_lo + abs(rng.gauss(0.0, params["spread"]))))
    elif kind == "easy_correlated":
        value = band_lo + (0.15 + 0.5 * shared) * (band_hi - band_lo)
    # SUBTLE: shifted by k_sigma or with inflated variance, clamped to the normal support so
    # every value is also a value normal data could take.
    elif kind == "subtle_spike":
        value = rng.gauss(mean + params["k_sigma"] * std, 0.25 * std)
    elif kind == "subtle_level_shift":
        value = rng.gauss(mean + params["k_sigma"] * std, std)
    elif kind == "subtle_drift":
        value = rng.gauss(mean + progress * params["k_sigma"] * std, std)
    elif kind == "subtle_variance":
        value = rng.gauss(mean, params["variance_factor"] * std)
    elif kind == "subtle_correlated":
        value = mean + shared * params["k_sigma"] * std + rng.gauss(0.0, 0.25 * std)
    else:
        raise ValueError(f"unknown anomaly kind {kind!r}")

    if kind.startswith("subtle_"):
        value = min(hi, max(lo, value))
    return round(value, 2)


def plan_metric_params(rng, scenario_type, difficulty, metric_type):
    """Per-episode constants for one metric (drawn once, at planning time)."""
    band_lo, band_hi = EASY_BANDS[metric_type]
    width = band_hi - band_lo
    k = round(rng.uniform(*SUBTLE_SIGMA_RANGE), 3)

    if difficulty == "EASY":
        if scenario_type == "SPIKE":
            return {"kind": "easy_spike"}
        if scenario_type == "LEVEL_SHIFT":
            return {"kind": "easy_level_shift", "level": round(rng.uniform(band_lo + 0.25 * width, band_hi - 0.25 * width), 3)}
        if scenario_type == "DRIFT":
            return {"kind": "easy_drift", "target": round(rng.uniform(band_lo + 0.5 * width, band_hi), 3)}
        if scenario_type == "VARIANCE":
            return {"kind": "easy_variance", "spread": round(rng.uniform(0.25, 0.5) * width, 3)}
        return {"kind": "easy_correlated"}

    if scenario_type == "SPIKE":
        return {"kind": "subtle_spike", "k_sigma": k}
    if scenario_type == "LEVEL_SHIFT":
        return {"kind": "subtle_level_shift", "k_sigma": k}
    if scenario_type == "DRIFT":
        return {"kind": "subtle_drift", "k_sigma": k}
    if scenario_type == "VARIANCE":
        return {"kind": "subtle_variance", "variance_factor": k}
    return {"kind": "subtle_correlated", "k_sigma": k}


# --- plans: the ordered sequence of episodes for a run ---------------------------------------

def build_episode(rng, episode_id, service, scenario_type, difficulty, start_s, duration_s, metrics=None):
    if metrics is None:
        metrics = list(rng.choice(CORRELATED_PAIRS)) if scenario_type == "CORRELATED" else [rng.choice(METRIC_TYPES)]
    metric_params = {m: plan_metric_params(rng, scenario_type, difficulty, m) for m in metrics}
    return Episode(episode_id, service, scenario_type, difficulty, start_s, start_s + duration_s, metric_params)


def continuous_plan(seed, services):
    """Endless, randomized episodes: random service, scenario, difficulty, metric(s), start and
    duration, one at a time with quiet gaps between. Fully determined by the seed."""
    rng = rng_stream(seed, "plan")
    t = 0.0
    n = 0
    while True:
        n += 1
        start = t + round(rng.uniform(*GAP_MINUTES) * 60)
        service = rng.choice(services)
        scenario_type = rng.choice(SCENARIO_TYPES)
        difficulty = rng.choice(DIFFICULTIES)
        duration = round(rng.uniform(*DURATION_MINUTES[scenario_type]) * 60)
        episode = build_episode(rng, f"ep-{n:04d}", service, scenario_type, difficulty, start, duration)

        if service in SECURITY_BY_SERVICE and rng.random() < SECURITY_ATTACH_PROBABILITY:
            event_type, severity = SECURITY_BY_SERVICE[service]
            episode.security = SecurityComponent(
                event_type, severity, start, start + min(duration, SECURITY_MAX_WINDOW_SECONDS),
                SECURITY_EMIT_PROBABILITY[difficulty],
            )

        yield episode
        t = episode.end_s


def demo_plan(schedule_path):
    """The existing anomaly_schedule.json, unchanged: same services, windows and magnitudes.
    Each metric anomaly becomes a SPIKE/EASY episode with its legacy value range; each security
    entry is attached to the metric anomaly on the same service whose window it overlaps."""
    with open(schedule_path) as f:
        data = json.load(f)

    episodes: List[Episode] = []
    for i, entry in enumerate(data.get("metric_anomalies", []), 1):
        metric = ANOMALY_METRIC_MAP[entry["anomaly_type"]]
        low, high = ANOMALY_TARGET_RANGES[metric][entry["magnitude"]]
        start = entry["start_minute"] * 60
        episodes.append(Episode(
            f"demo-{i}", entry["service"], "SPIKE", "EASY",
            start, start + entry["duration_minutes"] * 60,
            {metric: {"kind": "legacy_range", "range": [low, high]}},
            label_params={"anomaly_type": entry["anomaly_type"], "magnitude": entry["magnitude"]},
        ))

    for entry in data.get("security_events", []):
        start = entry["start_minute"] * 60
        end = start + entry["duration_minutes"] * 60
        owner = next((e for e in episodes if e.service == entry["service"] and e.start_s < end and start < e.end_s), None)
        if owner is None or owner.security is not None:
            raise ValueError(f"security entry {entry} has no single overlapping metric anomaly on {entry['service']}")
        owner.security = SecurityComponent(entry["event_type"], entry["severity"], start, end, 1.0)

    return sorted(episodes, key=lambda e: (e.start_s, e.episode_id))


# --- cascade mode: a root anomaly that propagates to its callers, plus unrelated distractors ---

CASCADE_GAP_MINUTES = (6, 15)          # quiet time between cascades
CASCADE_DURATION_MINUTES = (3, 8)      # how long the root anomaly lasts
SYMPTOM_DELAY_SECONDS = (10, 60)       # each hop starts this much after the service it calls
SYMPTOM_END_LAG_SECONDS = (0, 30)      # symptoms outlast the root slightly
SYMPTOM_PROPAGATION_PROBABILITY = 0.85  # chance each caller shows a symptom
HARD_CASCADE_PROBABILITY = 0.3         # root subtle (slow drift), symptoms clearly visible
ROOT_SECURITY_PROBABILITY = 0.3
DISTRACTOR_COUNTS = [0, 1, 1, 2]       # drawn uniformly: 0, 1 or 2 distractors per cascade
DISTRACTOR_OFFSET_SECONDS = (-60, 180)  # distractor start relative to the root start
DISTRACTOR_DURATION_MINUTES = (1.5, 5)
ROOT_METRICS = {"DATABASE": ["LATENCY", "ERROR_RATE", "CPU", "DISK"],
                "MICROSERVICE": ["ERROR_RATE", "LATENCY", "CPU", "MEMORY"]}
SYMPTOM_METRICS = ["LATENCY", "ERROR_RATE"]  # how a failing dependency shows up in its callers


def _easy_level(rng, metric_type, low_frac, high_frac):
    band_lo, band_hi = EASY_BANDS[metric_type]
    return {"kind": "easy_level_shift", "level": round(band_lo + rng.uniform(low_frac, high_frac) * (band_hi - band_lo), 3)}


def cascade_plan(seed, services):
    """Endless cascades. Each one: a ROOT anomaly on a service that has callers, SYMPTOM anomalies
    on its callers (transitively, along CALL_GRAPH) starting 10-60 s after the service they call,
    and 0-2 independent DISTRACTOR anomalies on services outside the cascade at overlapping times.

    EASY cascades: strong root (upper EASY band), weaker symptoms (lower EASY band).
    HARD cascades: subtle, slowly drifting root (1.5-3 sigma by the end) with clearly visible
    symptoms, so a detector often sees a symptom before (or instead of) the root.
    Labels carry groupId, role (ROOT/SYMPTOM/DISTRACTOR), rootService and cascadeType in params;
    each distractor is its own single-member group and never shares a cascade's groupId."""
    from topology import SEED_SERVICES, callers_of

    rng = rng_stream(seed, "cascade-plan")
    types = {s["name"]: s["type"] for s in SEED_SERVICES}
    callers = callers_of()
    roots = [s for s in services if any(c in services for c in callers.get(s, []))]
    t = 0.0
    n = 0
    while True:
        n += 1
        group = f"cascade-{n:04d}"
        hard = rng.random() < HARD_CASCADE_PROBABILITY
        cascade_type = "HARD" if hard else "EASY"
        root = rng.choice(roots)
        start = t + round(rng.uniform(*CASCADE_GAP_MINUTES) * 60)
        end = start + round(rng.uniform(*CASCADE_DURATION_MINUTES) * 60)
        metric = rng.choice(ROOT_METRICS[types.get(root, "MICROSERVICE")])
        if hard:
            root_ep = Episode(f"{group}-root", root, "DRIFT", "SUBTLE", start, end,
                              {metric: {"kind": "subtle_drift", "k_sigma": round(rng.uniform(*SUBTLE_SIGMA_RANGE), 3)}})
        else:
            root_ep = Episode(f"{group}-root", root, "LEVEL_SHIFT", "EASY", start, end, {metric: _easy_level(rng, metric, 0.5, 0.9)})
        root_ep.label_params = {"groupId": group, "role": "ROOT", "rootService": root, "cascadeType": cascade_type}
        if root in SECURITY_BY_SERVICE and rng.random() < ROOT_SECURITY_PROBABILITY:
            event_type, severity = SECURITY_BY_SERVICE[root]
            root_ep.security = SecurityComponent(event_type, severity, start, start + min(end - start, SECURITY_MAX_WINDOW_SECONDS),
                                                 SECURITY_EMIT_PROBABILITY["EASY"])
        members = [root_ep]

        # Symptoms: breadth-first towards callers, each hop delayed from the service it calls.
        frontier, seen = [(root, start)], {root}
        while frontier:
            callee, onset = frontier.pop(0)
            for caller in callers.get(callee, []):
                if caller in seen or caller not in services:
                    continue
                if rng.random() >= SYMPTOM_PROPAGATION_PROBABILITY:
                    continue
                seen.add(caller)
                s_start = onset + round(rng.uniform(*SYMPTOM_DELAY_SECONDS))
                s_end = max(end, s_start + 60) + round(rng.uniform(*SYMPTOM_END_LAG_SECONDS))
                s_metric = rng.choice(SYMPTOM_METRICS)
                params = _easy_level(rng, s_metric, 0.5, 0.9) if hard else _easy_level(rng, s_metric, 0.05, 0.25)
                ep = Episode(f"{group}-sym-{caller}", caller, "LEVEL_SHIFT", "EASY", s_start, s_end, {s_metric: params},
                             label_params={"groupId": group, "role": "SYMPTOM", "rootService": root, "cascadeType": cascade_type})
                members.append(ep)
                frontier.append((caller, s_start))

        # Distractors: unrelated services, overlapping in time, each its own group.
        unrelated = [s for s in services if s not in seen]
        distractors = []
        for i in range(min(rng.choice(DISTRACTOR_COUNTS), len(unrelated))):
            svc = rng.choice(unrelated)
            unrelated.remove(svc)
            d_start = max(t, start + round(rng.uniform(*DISTRACTOR_OFFSET_SECONDS)))
            d_metric = rng.choice(METRIC_TYPES)
            scenario = rng.choice(["SPIKE", "LEVEL_SHIFT"])
            ep = build_episode(rng, f"{group}-dis-{i + 1}", svc, scenario, "EASY", d_start,
                               round(rng.uniform(*DISTRACTOR_DURATION_MINUTES) * 60), [d_metric])
            ep.label_params = {"groupId": f"{group}-dis-{i + 1}", "role": "DISTRACTOR", "rootService": svc}
            distractors.append(ep)

        episodes = sorted(members + distractors, key=lambda e: (e.start_s, e.episode_id))
        for ep in episodes:
            yield ep
        t = max(e.end_s for e in episodes)


def make_plan(mode, seed, services, schedule_path=None) -> Iterator[Episode]:
    if mode == "demo":
        return iter(demo_plan(schedule_path))
    if mode == "continuous":
        return continuous_plan(seed, services)
    if mode == "normal":
        return iter(())
    if mode == "cascade":
        return cascade_plan(seed, services)
    raise ValueError(f"unknown SCENARIO_MODE {mode!r} (expected demo, continuous, normal or cascade)")
