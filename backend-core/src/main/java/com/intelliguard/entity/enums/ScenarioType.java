package com.intelliguard.entity.enums;

// Shape of an anomaly the simulator deliberately injected (ground truth) - not a detection.
public enum ScenarioType {
    SPIKE,
    LEVEL_SHIFT,
    DRIFT,
    VARIANCE,
    CORRELATED
}
