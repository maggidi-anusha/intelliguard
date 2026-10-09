"""Shared detector interface.

A *frame* is one service's metrics as a DataFrame: a sorted timestamp index and one float column
per metric type (METRICS). Every detector implements:

    fit(train)                 train: {service_id: [frame, ...]} of normal-only data
    score(frame, service_id)   -> ScoreResult (per-row scores and flags)

Scores are causal: a row's score only uses that row and earlier rows, so scoring a whole run
at once gives the same answer as scoring it tick by tick.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional

import pandas as pd

# Same order the simulator generates them in.
METRICS = ["CPU", "MEMORY", "DISK", "NETWORK", "LATENCY", "ERROR_RATE"]

TrainData = Dict[int, List[pd.DataFrame]]


@dataclass
class ScoreResult:
    """Per-row output for one service.

    metric_scores/metric_flags: one column per metric; all-NaN/False for detectors that only
    score the service as a whole (L2).
    raw: the service-level score the threshold is applied to (unbounded, detector-specific).
    service_score: raw mapped to 0-1, where 0.5 sits exactly at the detector's threshold.
    valid: False where there isn't enough history yet to score (never flagged).
    """
    metric_scores: pd.DataFrame
    metric_flags: pd.DataFrame
    raw: pd.Series
    service_score: pd.Series
    service_flag: pd.Series
    valid: pd.Series


class Detector:
    name = "base"
    min_history = 0  # rows needed before the first scored row

    def fit(self, train: TrainData) -> "Detector":
        return self

    def score(self, frame: pd.DataFrame, service_id: Optional[int] = None) -> ScoreResult:
        raise NotImplementedError

    def params(self) -> dict:
        return {}


def ratio_to_unit(raw, threshold):
    """Map a non-negative score to [0, 1) with threshold -> 0.5 (monotonic)."""
    raw = raw.clip(lower=0)
    return raw / (raw + threshold)
