package com.intelliguard.ml;

import java.time.Instant;
import java.util.List;

// Body of ml-service POST /copilot/ask: the question plus this incident's context, built by
// CopilotService from the incident detail. The copilot answers ONLY from this context and the
// runbooks; notes are passed as untrusted free text that it quotes, never follows.
public record MlCopilotRequest(String question, Context context) {

    public record Context(Summary incident, RootCause rootCause, List<ServiceCtx> services, List<AnomalyCtx> anomalies,
                          List<TimelineCtx> timeline, List<NoteCtx> notes, List<SecurityCtx> securityEvents) {
    }

    public record Summary(Long id, String title, String status, String severity, String peakSeverity,
                          Instant startedAt, Instant lastActivityAt, Instant resolvedAt, String acknowledgedBy) {
    }

    public record RootCause(Double confidence, List<Candidate> candidates) {
    }

    public record Candidate(Long serviceId, String serviceName, Integer rank, Double score, List<String> evidence) {
    }

    public record ServiceCtx(Long serviceId, String name, String type, String criticality, String currentRiskLevel,
                             Double currentRiskScore) {
    }

    public record AnomalyCtx(Long id, String serviceName, String metricType, String detector, String status,
                             Instant start, Instant end, Double peak) {
    }

    public record TimelineCtx(Instant at, String type, String serviceName, String detail, String by) {
    }

    public record NoteCtx(String author, String text, Instant createdAt) {
    }

    public record SecurityCtx(String serviceName, String eventType, long count) {
    }
}
