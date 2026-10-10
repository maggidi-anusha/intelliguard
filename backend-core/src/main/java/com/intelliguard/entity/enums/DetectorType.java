package com.intelliguard.entity.enums;

// Which detector produced an anomaly: L0 = Phase 3 fixed thresholds (computed in backend-core),
// L1 = ml-service rolling robust z-score, L2 = ml-service Isolation Forest (not deployed).
public enum DetectorType {
    L0,
    L1,
    L2
}
