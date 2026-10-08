package com.intelliguard.entity.enums;

// Deterministic, threshold-based status for Phase 3 - NOT an ML prediction. Phase 4 will
// introduce learned anomaly detection separately; this stays a fixed-rule classification
// (see DashboardService) so it must never be described as "AI-detected" in UI copy.
public enum HealthStatus {
    HEALTHY,
    DEGRADED,
    UNKNOWN
}
