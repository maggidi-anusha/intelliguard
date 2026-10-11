package com.intelliguard.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

// The parts of ml-service's POST /correlate response backend-core uses. rootCause is kept as the
// raw map ({confidence, candidates: [{serviceId, score, rank, components, evidence}]}) and stored
// on the incident as-is.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlCorrelateResponse(
        String method,
        String profile,
        Map<String, Object> params,
        String confidenceMethod,
        String asOf,
        List<Group> incidents
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Group(
            String incidentId,
            List<Long> episodeIds,
            List<Long> services,
            Map<String, Object> rootCause
    ) {
    }
}
