package com.intelliguard.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// ml-service's copilot answer, passed to the frontend as-is. mode is always "extractive": every
// sentence is templated from the incident context or quoted verbatim from a runbook.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlCopilotResponse(
        String answer,
        List<Citation> citations,
        String mode,
        String intent,
        boolean grounded,
        boolean refused,
        String reason
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Citation(String type, String id, String title, String excerpt) {
    }
}
