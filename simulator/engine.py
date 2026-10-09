"""Tick-by-tick telemetry + ground-truth generation, shared by the live simulator (main.py)
and the offline dataset generator (offline.py) so the two can never drift apart.

Given the same seed, mode, services and tick timestamps, SimulationEngine produces exactly the
same rows and labels. Labels describe what was *injected* (evaluation ground truth). They are
never detections, and the engine never looks at anything a detector produces.
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional

from episodes import Episode, anomaly_value, correlated_factor, make_plan
from security_events import build_event_details
from telemetry_generator import METRIC_TYPES, baseline_stats, generate_logs, normal_value, rng_stream, service_jitter
from topology import SEED_SERVICES

SCENARIO_MODES = ("demo", "continuous", "normal")


def format_ts(ts: datetime) -> str:
    """ISO-8601 UTC with microseconds and a Z suffix - one format for API payloads and CSVs."""
    return ts.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def resolve_seed(configured):
    """(seed, generated): the configured SIMULATOR_SEED, or a fresh random one when unset."""
    if configured is None or str(configured).strip() == "":
        return random.SystemRandom().randrange(1, 2**31), True
    return int(str(configured).strip()), False


def make_run_id(prefix, mode, start: datetime, seed):
    return f"{prefix}-{mode}-{start.astimezone(timezone.utc):%Y%m%dT%H%M%SZ}-s{seed}"


@dataclass
class MetricRow:
    timestamp: datetime
    service_id: int
    service_name: str
    metric_type: str
    value: float
    injected: bool  # in-memory only (tests/accounting) - never written to metric outputs


@dataclass
class LogRow:
    timestamp: datetime
    service_id: int
    service_name: str
    level: str
    message: str


@dataclass
class SecurityRow:
    timestamp: datetime
    service_id: int
    service_name: str
    event_type: str
    severity: str
    details: dict


@dataclass
class TickResult:
    metrics: List[MetricRow] = field(default_factory=list)
    logs: List[LogRow] = field(default_factory=list)
    security: List[SecurityRow] = field(default_factory=list)
    labels: List[dict] = field(default_factory=list)  # episodes that finished before this tick


@dataclass
class _Injected:
    """First/last timestamp and count of the ticks actually injected for one label row."""
    first: Optional[datetime] = None
    last: Optional[datetime] = None
    count: int = 0

    def add(self, ts):
        self.first = self.first or ts
        self.last = ts
        self.count += 1


@dataclass
class _EpisodeState:
    episode: Episode
    metrics: Dict[str, _Injected] = field(default_factory=dict)
    security: _Injected = field(default_factory=_Injected)


class SimulationEngine:
    def __init__(self, seed, mode, run_start: datetime, services: Dict[str, int], run_id,
                 schedule_path=None, plan=None):
        if mode not in SCENARIO_MODES:
            raise ValueError(f"unknown SCENARIO_MODE {mode!r} (expected one of {', '.join(SCENARIO_MODES)})")
        self.seed = seed
        self.mode = mode
        self.run_start = run_start
        self.run_id = run_id
        # Telemetry is generated for every registered service (as before); episodes only ever
        # target the simulator's own topology.
        self.services = dict(services)
        topology = [s["name"] for s in SEED_SERVICES if s["name"] in self.services]
        self._plan = iter(plan) if plan is not None else make_plan(mode, seed, topology, schedule_path)
        self._next = next(self._plan, None)
        self._active: List[_EpisodeState] = []
        self.skipped_episodes: List[str] = []  # planned but never injected (e.g. Mac asleep)

        self._jitter: Dict[str, float] = {}
        self._value_rng: Dict[str, random.Random] = {}
        self._log_rng: Dict[str, random.Random] = {}
        self._security_rng = rng_stream(seed, "security")
        self.trace_rng = rng_stream(seed, "tracing")

    # -- helpers --------------------------------------------------------------------------

    def elapsed_seconds(self, ts: datetime) -> float:
        return (ts - self.run_start).total_seconds()

    def _rng(self, cache, kind, service_name):
        if service_name not in cache:
            cache[service_name] = rng_stream(self.seed, kind, service_name)
        return cache[service_name]

    def jitter(self, service_name):
        if service_name not in self._jitter:
            self._jitter[service_name] = service_jitter(self.seed, service_name)
        return self._jitter[service_name]

    def active_metric_types(self, service_name, ts: datetime):
        """Metrics currently being injected on a service (used by tracing to shape spans)."""
        elapsed = self.elapsed_seconds(ts)
        return {m for st in self._active if st.episode.service == service_name and st.episode.is_active(elapsed)
                for m in st.episode.metric_params}

    # -- the tick ---------------------------------------------------------------------------

    def tick(self, ts: datetime) -> TickResult:
        elapsed = self.elapsed_seconds(ts)
        result = TickResult()

        # Episodes that ended before this tick are complete: emit their labels.
        still_active = []
        for state in self._active:
            if elapsed >= state.episode.end_s:
                result.labels.extend(self._labels_for(state, created_at=ts, partial=False))
            else:
                still_active.append(state)
        self._active = still_active

        # Start episodes whose planned start has been reached. One whose whole window already
        # passed without a tick (e.g. the host slept through it) injected nothing, so it gets
        # no label.
        while self._next is not None and self._next.start_s <= elapsed:
            episode = self._next
            self._next = next(self._plan, None)
            if episode.end_s <= elapsed or episode.service not in self.services:
                self.skipped_episodes.append(episode.episode_id)
                continue
            self._active.append(_EpisodeState(episode))

        by_service: Dict[str, List[_EpisodeState]] = {}
        for state in self._active:
            by_service.setdefault(state.episode.service, []).append(state)

        for name, service_id in self.services.items():
            states = by_service.get(name, [])
            rng = self._rng(self._value_rng, "values", name)
            jitter = self.jitter(name)

            shared = {id(st): correlated_factor(rng, st.episode.difficulty)
                      for st in states if st.episode.scenario_type == "CORRELATED"}
            targeted = {m: st for st in states for m in st.episode.metric_params}

            for metric_type in METRIC_TYPES:
                state = targeted.get(metric_type)
                if state is None:
                    value = normal_value(rng, metric_type, jitter)
                else:
                    episode = state.episode
                    progress = (elapsed - episode.start_s) / (episode.end_s - episode.start_s)
                    value = anomaly_value(rng, metric_type, episode.metric_params[metric_type], jitter,
                                          progress, shared.get(id(state)))
                    state.metrics.setdefault(metric_type, _Injected()).add(ts)
                result.metrics.append(MetricRow(ts, service_id, name, metric_type, value, state is not None))

            for level, message in generate_logs(self._rng(self._log_rng, "logs", name), [s.episode for s in states]):
                result.logs.append(LogRow(ts, service_id, name, level, message))

        for state in self._active:
            sec = state.episode.security
            if sec is not None and sec.is_active(elapsed) and self._security_rng.random() < sec.emit_probability:
                name = state.episode.service
                result.security.append(SecurityRow(ts, self.services[name], name, sec.event_type, sec.severity,
                                                   build_event_details(self._security_rng, sec.event_type)))
                state.security.add(ts)

        return result

    def flush(self, now: datetime) -> List[dict]:
        """Labels for every episode still open (call on shutdown / end of an offline run).
        An episode whose planned window hadn't finished by `now` is marked partial."""
        elapsed = self.elapsed_seconds(now)
        labels = []
        for state in self._active:
            labels.extend(self._labels_for(state, created_at=now, partial=elapsed < state.episode.end_s))
        self._active = []
        return labels

    # -- labels -----------------------------------------------------------------------------

    def _labels_for(self, state: _EpisodeState, created_at: datetime, partial: bool) -> List[dict]:
        episode = state.episode
        common = {
            "episode_id": episode.episode_id,
            "planned_start": format_ts(self._at(episode.start_s)),
            "planned_end": format_ts(self._at(episode.end_s)),
            "partial": partial,
            **episode.label_params,
        }
        rows = []
        jitter = self.jitter(episode.service)
        for metric_type, params in episode.metric_params.items():
            injected = state.metrics.get(metric_type)
            if injected is None:
                continue
            mean, std, _, _ = baseline_stats(metric_type, jitter)
            extra = {"generator": params, "baseline_mean": round(mean, 4), "baseline_std": std,
                     "injected_ticks": injected.count}
            if episode.scenario_type == "CORRELATED":
                extra["correlated_with"] = [m for m in episode.metric_params if m != metric_type]
            rows.append(self._label_row(episode, "METRIC", metric_type, injected, created_at, {**common, **extra}))

        sec = episode.security
        if sec is not None and state.security.count:
            extra = {"event_type": sec.event_type, "severity": sec.severity,
                     "emit_probability": sec.emit_probability, "event_count": state.security.count}
            rows.append(self._label_row(episode, "SECURITY", None, state.security, created_at, {**common, **extra}))
        return rows

    def _label_row(self, episode, signal_type, metric_type, injected: _Injected, created_at, params):
        return {
            "run_id": self.run_id,
            "seed": self.seed,
            "service_id": self.services[episode.service],
            "signal_type": signal_type,
            "metric_type": metric_type,
            "scenario_type": episode.scenario_type,
            "difficulty": episode.difficulty,
            "start_time": injected.first,
            "end_time": injected.last,
            "params": params,
            "created_at": created_at,
        }

    def _at(self, offset_s) -> datetime:
        return self.run_start + timedelta(seconds=offset_s)
