"""L2: one Isolation Forest per service over all six metrics at once.

Features per row, for each metric (18 in total):
    value                      the current value
    value - rolling mean       vs the previous `window` rows (row itself excluded)
    short std - rolling std    std of the last `short_window` rows minus std of the previous `window`
Trained on normal-only data. The service is flagged when the anomaly score (sklearn's
-score_samples, roughly 0..1, higher = more anomalous) exceeds `threshold`. It scores the
service as a whole, so there are no per-metric scores.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest

from .base import METRICS, Detector, ScoreResult


class IsolationForestDetector(Detector):
    name = "L2"

    def __init__(self, window=60, short_window=6, threshold=0.6, n_estimators=200, max_samples=256, random_state=0):
        self.window = int(window)
        self.short_window = int(short_window)
        self.threshold = float(threshold)
        self.n_estimators = int(n_estimators)
        self.max_samples = max_samples
        self.random_state = int(random_state)
        self.models = {}

    @property
    def min_history(self):
        return self.window

    def features(self, frame):
        cols = {}
        for metric in METRICS:
            x = frame[metric].astype(float)
            prev = x.shift(1).rolling(self.window, min_periods=self.window)
            cols[f"{metric}_value"] = x
            cols[f"{metric}_dmean"] = x - prev.mean()
            cols[f"{metric}_dstd"] = x.rolling(self.short_window, min_periods=self.short_window).std() - prev.std()
        feats = pd.DataFrame(cols, index=frame.index)
        return feats, feats.notna().all(axis=1)

    def fit(self, train):
        for service_id, frames in train.items():
            parts = []
            for f in frames:
                feats, valid = self.features(f)
                parts.append(feats[valid].to_numpy())
            model = IsolationForest(n_estimators=self.n_estimators, max_samples=self.max_samples,
                                    random_state=self.random_state)
            self.models[int(service_id)] = model.fit(np.vstack(parts))
        return self

    def score(self, frame, service_id=None):
        model = self.models[int(service_id)]  # KeyError for a service it wasn't trained on
        feats, valid = self.features(frame)
        raw = pd.Series(0.0, index=frame.index)
        if valid.any():
            raw[valid] = -model.score_samples(feats[valid].to_numpy())
        empty = pd.DataFrame(np.nan, index=frame.index, columns=METRICS)
        flag = (raw > self.threshold) & valid
        # IF scores already live in ~[0, 1]; shift so the threshold maps to 0.5.
        service_score = (raw - self.threshold + 0.5).clip(0.0, 1.0).where(valid, 0.0)
        return ScoreResult(empty, empty.notna(), raw, service_score, flag, valid)

    def params(self):
        return {"window": self.window, "short_window": self.short_window, "threshold": round(self.threshold, 4),
                "n_estimators": self.n_estimators, "max_samples": self.max_samples, "random_state": self.random_state}
