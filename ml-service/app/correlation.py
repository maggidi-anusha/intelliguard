"""Incident correlation and root-cause ranking for POST /correlate.

This is a HEURISTIC, GRAPH-BASED method - not a learned model. Nothing is trained; every output
follows from the rules below and the parameters in app/correlation_config.json.

Correlation: two anomaly episodes are linked when
  (a) their active windows overlap or are at most `gap_seconds` apart, and
  (b) their services are the same or at most `max_hops` apart in the dependency graph
      (edges taken as undirected for this distance).
Incidents are the connected components of that link graph, so anomalies that happen at the same
time on unconnected services stay separate incidents. An episode without an end time is treated
as still active up to `asOf` = the latest start/end time in the request.

Root cause: every service in an incident is a candidate, scored as a weighted sum of five inputs,
each normalized to [0, 1] within the incident:
  onset        1 for the earliest onset, 0 for the latest (linear in between; 1 for all if equal)
  dependents   other anomalous services in the incident that depend on it, directly or
               transitively (following edges in their direction), / (number of candidates - 1)
  peak         its highest episode peakScore / the highest peakScore in the incident
  security     1 if it had any security events in the window, else 0
  criticality  LOW 0.25, MEDIUM 0.5, HIGH 0.75, CRITICAL 1.0 (0.5 if unknown)
Ties are broken by earlier onset, then lower serviceId. Confidence = (score #1 - score #2) / score #1:
0 means a tie, 1 means the runner-up scored nothing; it is 1 for a single-candidate incident.
"""
from __future__ import annotations

import json
import os
from collections import deque
from datetime import datetime, timezone
from typing import Dict, List, Literal, Optional, Union

from pydantic import BaseModel, Field, field_validator, model_validator

METHOD = "heuristic graph-based correlation and root-cause ranking (not a learned model)"
CONFIDENCE_METHOD = "(score of rank 1 - score of rank 2) / score of rank 1; 1.0 when there is a single candidate"
CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "correlation_config.json")
WEIGHT_KEYS = ("onset", "dependents", "peak", "security", "criticality")
CRITICALITY_VALUE = {"LOW": 0.25, "MEDIUM": 0.5, "HIGH": 0.75, "CRITICAL": 1.0}

MetricType = Literal["CPU", "MEMORY", "DISK", "NETWORK", "LATENCY", "ERROR_RATE"]
Criticality = Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]


# -- request / parameters ----------------------------------------------------------------------

class Weights(BaseModel):
    onset: float = Field(ge=0)
    dependents: float = Field(ge=0)
    peak: float = Field(ge=0)
    security: float = Field(ge=0)
    criticality: float = Field(ge=0)

    @model_validator(mode="after")
    def _sum_to_one(self):
        total = sum(getattr(self, k) for k in WEIGHT_KEYS)
        if abs(total - 1.0) > 1e-6:
            raise ValueError(f"weights must sum to 1, got {total}")
        return self


class Params(BaseModel):
    gap_seconds: float = Field(ge=0, le=86400)
    max_hops: int = Field(ge=0, le=10)
    weights: Weights


class EpisodeIn(BaseModel):
    id: Union[int, str]
    serviceId: int = Field(ge=1)
    metricType: Optional[MetricType] = None
    detector: Optional[str] = Field(default=None, max_length=16)
    start: datetime
    end: Optional[datetime] = None
    peakScore: float = Field(ge=0, allow_inf_nan=False)

    @model_validator(mode="after")
    def _end_after_start(self):
        if self.end is not None and _utc(self.end) < _utc(self.start):
            raise ValueError("end must not be before start")
        return self


class Edge(BaseModel):
    service: int = Field(ge=1)    # this service...
    dependsOn: int = Field(ge=1)  # ...calls / depends on this one


class CorrelateRequest(BaseModel):
    episodes: List[EpisodeIn] = Field(default_factory=list, max_length=5000)
    dependencies: List[Edge] = Field(default_factory=list, max_length=10000)
    criticality: Dict[int, Criticality] = Field(default_factory=dict)
    securityEvents: Dict[int, int] = Field(default_factory=dict)
    profile: Optional[str] = None     # a parameter profile from correlation_config.json
    params: Optional[Params] = None   # explicit override (experiments, tests)

    @field_validator("securityEvents")
    @classmethod
    def _non_negative(cls, v):
        if any(c < 0 for c in v.values()):
            raise ValueError("security event counts must be >= 0")
        return v

    @model_validator(mode="after")
    def _unique_ids(self):
        ids = [str(e.id) for e in self.episodes]
        if len(ids) != len(set(ids)):
            raise ValueError("episode ids must be unique")
        return self


def load_config(path=CONFIG_PATH):
    with open(path) as f:
        return json.load(f)


def resolve_params(request: CorrelateRequest, config=None) -> tuple[str, Params]:
    if request.params is not None:
        return "request", request.params
    config = config or load_config()
    name = request.profile or config["default_profile"]
    if name not in config["profiles"]:
        raise KeyError(f"unknown profile {name!r}; available: {sorted(config['profiles'])}")
    return name, Params(**config["profiles"][name])


# -- algorithm (pure functions) -------------------------------------------------------------------

def _utc(dt: datetime) -> datetime:
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt.astimezone(timezone.utc)


def _iso(dt: datetime) -> str:
    return _utc(dt).strftime("%Y-%m-%dT%H:%M:%SZ") if dt.microsecond == 0 else _utc(dt).isoformat().replace("+00:00", "Z")


def _hop_distances(services, dependencies, max_hops):
    """Undirected BFS distance (up to max_hops) between every pair of the given services."""
    adjacent = {}
    for e in dependencies:
        adjacent.setdefault(e.service, set()).add(e.dependsOn)
        adjacent.setdefault(e.dependsOn, set()).add(e.service)
    dist = {}
    for s in services:
        seen, queue = {s: 0}, deque([s])
        while queue:
            node = queue.popleft()
            if seen[node] == max_hops:
                continue
            for nxt in sorted(adjacent.get(node, ())):
                if nxt not in seen:
                    seen[nxt] = seen[node] + 1
                    queue.append(nxt)
        dist[s] = seen
    return dist


def _reachable(start, dependencies):
    """Services `start` depends on, directly or transitively (following edge direction)."""
    out = {}
    for e in dependencies:
        out.setdefault(e.service, set()).add(e.dependsOn)
    seen, stack = set(), [start]
    while stack:
        for nxt in out.get(stack.pop(), ()):
            if nxt not in seen:
                seen.add(nxt)
                stack.append(nxt)
    seen.discard(start)
    return seen


def correlate(request: CorrelateRequest, config=None) -> dict:
    profile, params = resolve_params(request, config)
    base = {"method": METHOD, "profile": profile, "params": params.model_dump(), "confidenceMethod": CONFIDENCE_METHOD}
    if not request.episodes:
        return {**base, "asOf": None, "incidents": []}

    eps = sorted(request.episodes, key=lambda e: (_utc(e.start), e.serviceId, str(e.id)))
    as_of = max(max(_utc(e.start), _utc(e.end) if e.end else _utc(e.start)) for e in eps)
    window = [(_utc(e.start), _utc(e.end) if e.end else as_of) for e in eps]
    services = sorted({e.serviceId for e in eps})
    dist = _hop_distances(services, request.dependencies, params.max_hops)

    # Union-find over episode pairs that are close in time AND close in the graph.
    parent = list(range(len(eps)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(eps)):
        for j in range(i + 1, len(eps)):
            gap = (max(window[i][0], window[j][0]) - min(window[i][1], window[j][1])).total_seconds()
            if gap <= params.gap_seconds and eps[j].serviceId in dist[eps[i].serviceId]:
                parent[find(j)] = find(i)

    components = {}
    for i in range(len(eps)):
        components.setdefault(find(i), []).append(i)
    ordered = sorted(components.values(), key=lambda idx: (window[idx[0]][0], min(eps[k].serviceId for k in idx)))

    incidents = []
    for n, idx in enumerate(ordered, 1):
        members = [eps[k] for k in idx]
        incidents.append({
            "incidentId": f"incident-{n}",
            "start": _iso(min(window[k][0] for k in idx)),
            "end": _iso(max(window[k][1] for k in idx)),
            "openEpisodes": sum(1 for e in members if e.end is None),
            "episodeIds": [e.id for e in members],
            "services": sorted({e.serviceId for e in members}),
            "rootCause": _rank(members, request, params),
        })
    return {**base, "asOf": _iso(as_of), "incidents": incidents}


def _rank(members, request, params):
    by_service = {}
    for e in members:
        by_service.setdefault(e.serviceId, []).append(e)
    candidates = sorted(by_service)
    onset = {s: min(_utc(e.start) for e in by_service[s]) for s in candidates}
    peak = {s: max(e.peakScore for e in by_service[s]) for s in candidates}
    first, last = min(onset.values()), max(onset.values())
    span = (last - first).total_seconds()
    max_peak = max(peak.values())
    dependents = {s: sorted(x for x in candidates if x != s and s in _reachable(x, request.dependencies)) for s in candidates}
    w = params.weights

    rows = []
    for s in candidates:
        delay = (onset[s] - first).total_seconds()
        norm = {
            "onset": 1.0 if span == 0 else 1.0 - delay / span,
            "dependents": len(dependents[s]) / (len(candidates) - 1) if len(candidates) > 1 else 0.0,
            "peak": peak[s] / max_peak if max_peak > 0 else 0.0,
            "security": 1.0 if request.securityEvents.get(s, 0) > 0 else 0.0,
            "criticality": CRITICALITY_VALUE.get(request.criticality.get(s), 0.5),
        }
        score = sum(getattr(w, k) * norm[k] for k in WEIGHT_KEYS)
        crit = request.criticality.get(s)
        evidence = [
            "earliest onset in the incident" if delay == 0 else f"onset {delay:.0f} s after the earliest anomalous service",
            (f"{len(dependents[s])} other anomalous service(s) depend on it: "
             + ", ".join(f"service {d}" for d in dependents[s])) if dependents[s]
            else "no other anomalous service in the incident depends on it",
            f"peak anomaly score {peak[s]:.3f}" + (" (highest in the incident)" if peak[s] == max_peak else f" (incident max {max_peak:.3f})"),
            f"{request.securityEvents[s]} security event(s) in the window" if request.securityEvents.get(s, 0) > 0
            else "no security events in the window",
            f"criticality {crit}" if crit else "criticality unknown (treated as MEDIUM)",
        ]
        rows.append({"serviceId": s, "score": round(score, 4), "_raw": score, "_onset": onset[s],
                     "components": {k: round(v, 4) for k, v in norm.items()}, "evidence": evidence})

    rows.sort(key=lambda r: (-r["_raw"], r["_onset"], r["serviceId"]))
    for rank, r in enumerate(rows, 1):
        r["rank"] = rank
    top, second = rows[0]["_raw"], rows[1]["_raw"] if len(rows) > 1 else None
    if second is None:
        confidence = 1.0
    else:
        confidence = (top - second) / top if top > 0 else 0.0
        if abs(top - second) < 1e-12:
            rows[0]["evidence"].append(f"tied with service {rows[1]['serviceId']}; ranked first by earlier onset / lower serviceId")
    for r in rows:
        del r["_raw"], r["_onset"]
    return {"confidence": round(max(0.0, confidence), 4), "candidates": rows}
