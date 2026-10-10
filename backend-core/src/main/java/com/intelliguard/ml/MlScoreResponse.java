package com.intelliguard.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

// The parts of ml-service's POST /score response backend-core uses. The response also carries
// ml-service's own L0 "baseline"; it is ignored here because backend-core evaluates L0 itself
// (L0Thresholds), which keeps working when ml-service is down.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlScoreResponse(
        Long serviceId,
        String detector,
        Double threshold,
        Integer minSamplesPerMetric,
        boolean insufficientData,
        Double serviceScore,
        @JsonProperty("isAnomalous") boolean anomalous,
        Map<String, MetricScore> metrics
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetricScore(
            String detector,
            Integer samples,
            boolean insufficientData,
            Double score,      // 0-1, 0.5 = detector threshold
            Double rawScore,
            @JsonProperty("isAnomalous") boolean anomalous
    ) {
    }
}
