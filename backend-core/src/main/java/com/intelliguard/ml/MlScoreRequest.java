package com.intelliguard.ml;

import java.time.Instant;
import java.util.List;

// Body of ml-service POST /score: one service's recent metric samples.
public record MlScoreRequest(Long serviceId, List<Sample> samples) {

    public record Sample(Instant timestamp, String metricType, Double value) {
    }
}
