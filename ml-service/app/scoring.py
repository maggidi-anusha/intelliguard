"""Stateless scoring: a window of one service's recent metric samples in, per-metric scores out.

The default detector (from model_config.json, chosen on VALIDATION data) scores each metric;
the L0 Phase 3 threshold rule is evaluated alongside for comparison. Nothing is stored between
requests and nothing touches the database.
"""
from __future__ import annotations

import os
from datetime import datetime
from typing import Dict, List, Literal

import joblib
import numpy as np
from pydantic import BaseModel, Field

from .detectors import METRICS, THRESHOLDS
from .train import MODEL_FILE, model_dir, train_and_save

MetricType = Literal["CPU", "MEMORY", "DISK", "NETWORK", "LATENCY", "ERROR_RATE"]


class Sample(BaseModel):
    timestamp: datetime
    metricType: MetricType
    value: float = Field(allow_inf_nan=False)


class ScoreRequest(BaseModel):
    serviceId: int = Field(ge=1)
    samples: List[Sample] = Field(min_length=1, max_length=20000)


class Scorer:
    def __init__(self, bundle):
        self.name = bundle["name"]
        self.detector = bundle["detector"]
        self.config = bundle["config"]
        if not hasattr(self.detector, "score_series"):
            # L2 scores the service as a whole; per-metric scoring needs a per-metric detector.
            raise ValueError(f"default detector {self.name} has no per-metric scoring; use L1")

    @classmethod
    def load(cls, directory=None):
        """Load the joblib model; train it first if it isn't there (first start without a build)."""
        directory = directory or model_dir()
        path = os.path.join(directory, MODEL_FILE)
        if not os.path.exists(path):
            train_and_save(directory)
        return cls(joblib.load(path))

    @property
    def min_samples(self):
        return self.detector.min_history + 1

    def score(self, request: ScoreRequest) -> dict:
        series: Dict[str, Dict[datetime, float]] = {}
        for s in sorted(request.samples, key=lambda s: s.timestamp):
            series.setdefault(s.metricType, {})[s.timestamp] = s.value  # duplicate timestamp: last one wins

        metrics, baseline = {}, {}
        threshold = self.detector.threshold
        for metric in METRICS:
            if metric not in series:
                continue
            values = np.fromiter(series[metric].values(), dtype=float)
            entry = {"detector": self.name, "samples": len(values), "insufficientData": len(values) < self.min_samples,
                     "score": None, "rawScore": None, "isAnomalous": False}
            if not entry["insufficientData"]:
                smoothed, valid = self.detector.score_series(values, request.serviceId, metric)
                raw = float(smoothed[-1])
                entry.update(rawScore=round(raw, 4), score=round(raw / (raw + threshold), 4), isAnomalous=raw > threshold)
            metrics[metric] = entry

            latest = float(values[-1])
            limit = THRESHOLDS.get(metric)
            baseline[metric] = {"detector": "L0", "value": latest,
                                "score": None if limit is None else round(latest / limit, 4),
                                "isAnomalous": limit is not None and latest > limit,
                                **({"noRule": True} if limit is None else {})}

        scored = [m for m in metrics.values() if not m["insufficientData"]]
        return {
            "serviceId": request.serviceId,
            "detector": self.name,
            "threshold": round(threshold, 4),
            "minSamplesPerMetric": self.min_samples,
            "insufficientData": not scored,
            "serviceScore": max(m["score"] for m in scored) if scored else None,
            "isAnomalous": any(m["isAnomalous"] for m in scored),
            "metrics": metrics,
            "baseline": {"detector": "L0", "isAnomalous": any(b["isAnomalous"] for b in baseline.values()),
                         "metrics": baseline},
        }
