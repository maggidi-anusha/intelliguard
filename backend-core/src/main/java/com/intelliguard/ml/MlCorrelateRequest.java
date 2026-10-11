package com.intelliguard.ml;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Body of ml-service POST /correlate (see ml-service/app/correlation.py). `end` is null while an
// episode is still open; peakScore is on the 0-1 scale with 0.5 = detector threshold.
public record MlCorrelateRequest(
        List<Episode> episodes,
        List<Edge> dependencies,
        Map<Long, String> criticality,
        Map<Long, Long> securityEvents,
        String profile
) {
    public record Episode(Long id, Long serviceId, String metricType, String detector,
                          Instant start, Instant end, double peakScore) {
    }

    public record Edge(Long service, Long dependsOn) {
    }
}
