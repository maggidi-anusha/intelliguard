package com.intelliguard.service;

import com.intelliguard.entity.enums.MetricType;

import java.util.Map;
import java.util.Optional;

// The Phase 3 fixed health thresholds ("L0"), shared by the dashboard's health badges and the
// risk engine so the two can never disagree. A metric violates its rule when its value is
// strictly greater than the limit; NETWORK deliberately has no rule.
public final class L0Thresholds {

    public static final Map<MetricType, Double> LIMITS = Map.of(
            MetricType.CPU, 90.0,
            MetricType.MEMORY, 90.0,
            MetricType.DISK, 90.0,
            MetricType.ERROR_RATE, 5.0,
            MetricType.LATENCY, 500.0
    );

    private L0Thresholds() {
    }

    public static Optional<Double> limit(MetricType type) {
        return Optional.ofNullable(LIMITS.get(type));
    }

    public static boolean violated(MetricType type, Double value) {
        Double limit = LIMITS.get(type);
        return limit != null && value != null && value > limit;
    }
}
