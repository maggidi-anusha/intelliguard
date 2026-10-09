"""L0 baseline: the exact Phase 3 health rule (backend-core DashboardService).

A value is anomalous if CPU/MEMORY/DISK > 90, ERROR_RATE > 5 or LATENCY > 500 (strictly
greater, as in DashboardService). NETWORK has no rule. No training, no history needed.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .base import METRICS, Detector, ScoreResult, ratio_to_unit

THRESHOLDS = {"CPU": 90.0, "MEMORY": 90.0, "DISK": 90.0, "ERROR_RATE": 5.0, "LATENCY": 500.0}


class ThresholdDetector(Detector):
    name = "L0"
    min_history = 0

    def score(self, frame, service_id=None):
        scores = pd.DataFrame(np.nan, index=frame.index, columns=METRICS)
        flags = pd.DataFrame(False, index=frame.index, columns=METRICS)
        for metric, limit in THRESHOLDS.items():
            if metric in frame:
                values = frame[metric]
                scores[metric] = values / limit  # 1.0 == exactly at the threshold
                flags[metric] = (values > limit).fillna(False)
        raw = scores.max(axis=1, skipna=True).fillna(0.0)
        return ScoreResult(scores, flags, raw, ratio_to_unit(raw, 1.0), flags.any(axis=1),
                           pd.Series(True, index=frame.index))

    def params(self):
        return {"thresholds": THRESHOLDS, "NETWORK": "no rule"}
