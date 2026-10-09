"""L1: per service and metric, a rolling robust z-score smoothed with an EWMA.

For each row t, the reference is the previous `window` values (row t itself excluded):
    z_t = (x_t - median) / max(1.4826 * MAD, floor)
    s_t = EWMA(|z|) with smoothing `alpha` (alpha=1 means no smoothing)
A metric is flagged when s_t > threshold. |z| is smoothed (not z) so variance anomalies,
which swing both ways, still accumulate. `floor` (from normal training data) stops a
near-constant window from turning tiny wiggles into huge z-scores.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from numpy.lib.stride_tricks import sliding_window_view
from scipy.signal import lfilter

from .base import METRICS, Detector, ScoreResult, ratio_to_unit


def _robust_sigma(values):
    values = np.asarray(values, dtype=float)
    med = np.median(values)
    return 1.4826 * float(np.median(np.abs(values - med)))


class RobustZDetector(Detector):
    name = "L1"

    def __init__(self, window=60, alpha=0.3, threshold=3.0, floor_fraction=0.25):
        self.window = int(window)
        self.alpha = float(alpha)
        self.threshold = float(threshold)
        self.floor_fraction = float(floor_fraction)
        self.floors = {}         # (service_id, metric) -> sigma floor
        self.metric_floors = {}  # metric -> fallback floor for services not seen in training

    @property
    def min_history(self):
        return self.window

    def fit(self, train):
        per_metric = {m: [] for m in METRICS}
        for service_id, frames in train.items():
            for metric in METRICS:
                values = np.concatenate([f[metric].to_numpy(float) for f in frames])
                sigma = _robust_sigma(values)
                self.floors[(int(service_id), metric)] = self.floor_fraction * sigma
                per_metric[metric].append(sigma)
        self.metric_floors = {m: self.floor_fraction * float(np.median(s)) for m, s in per_metric.items() if s}
        return self

    def _floor(self, service_id, metric):
        return self.floors.get((service_id, metric), self.metric_floors.get(metric, 1e-6))

    def score_series(self, values, service_id, metric):
        """(smoothed |z| per row, valid mask) for one metric's values in time order."""
        x = np.asarray(values, dtype=float)
        n, w = len(x), self.window
        smoothed = np.full(n, np.nan)
        valid = np.zeros(n, dtype=bool)
        if n <= w:
            return smoothed, valid

        windows = sliding_window_view(x[:-1], w)  # windows[i] = x[i : i + w], the history of row i + w
        med = np.median(windows, axis=1)
        mad = np.median(np.abs(windows - med[:, None]), axis=1)
        sigma = np.maximum(1.4826 * mad, max(self._floor(service_id, metric), 1e-9))
        abs_z = np.abs((x[w:] - med) / sigma)

        # Causal EWMA, seeded with the first |z| (lfilter: y[n] = a*x[n] + (1-a)*y[n-1]).
        a = self.alpha
        ewma, _ = lfilter([a], [1.0, -(1.0 - a)], abs_z, zi=[(1.0 - a) * abs_z[0]])
        smoothed[w:] = ewma
        valid[w:] = True
        return smoothed, valid

    def score(self, frame, service_id=None):
        scores = pd.DataFrame(np.nan, index=frame.index, columns=METRICS)
        valid_any = np.zeros(len(frame), dtype=bool)
        for metric in METRICS:
            if metric in frame:
                s, valid = self.score_series(frame[metric].to_numpy(float), service_id, metric)
                scores[metric] = s
                valid_any |= valid
        flags = (scores > self.threshold).fillna(False)
        raw = scores.max(axis=1, skipna=True).fillna(0.0)
        return ScoreResult(scores, flags, raw, ratio_to_unit(raw, self.threshold), raw > self.threshold,
                           pd.Series(valid_any, index=frame.index))

    def params(self):
        return {"window": self.window, "alpha": self.alpha, "threshold": round(self.threshold, 4),
                "floor_fraction": self.floor_fraction}
