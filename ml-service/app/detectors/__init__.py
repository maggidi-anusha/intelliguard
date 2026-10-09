from .base import METRICS, Detector, ScoreResult
from .l0_threshold import THRESHOLDS, ThresholdDetector
from .l1_robust_z import RobustZDetector
from .l2_iforest import IsolationForestDetector

DETECTORS = {"L0": ThresholdDetector, "L1": RobustZDetector, "L2": IsolationForestDetector}


def build(name, params=None):
    """Instantiate a detector by name with constructor params (e.g. from model_config.json)."""
    return ThresholdDetector() if name == "L0" else DETECTORS[name](**(params or {}))


__all__ = ["METRICS", "THRESHOLDS", "DETECTORS", "Detector", "ScoreResult", "ThresholdDetector",
           "RobustZDetector", "IsolationForestDetector", "build"]
