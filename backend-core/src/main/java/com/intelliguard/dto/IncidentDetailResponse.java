package com.intelliguard.dto;

import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.RiskLevel;
import com.intelliguard.entity.enums.ServiceType;
import com.intelliguard.entity.enums.Severity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// GET /api/incidents/{id}: the incident, its services (with their current risk), its anomaly
// episodes, the ranked root-cause candidates with evidence, a timeline of anomaly start/end
// events plus the incident's own events, and (Phase 6A) its notes and acknowledgement.
public record IncidentDetailResponse(
        Long id,
        String title,
        IncidentStatus status,
        Severity severity,
        Severity peakSeverity,
        Instant startedAt,
        Instant lastActivityAt,
        Instant resolvedAt,
        Instant createdAt,
        Long mergedIntoId,
        String method,
        String acknowledgedBy,
        Instant acknowledgedAt,
        RootCause rootCause,
        List<ServiceEntry> services,
        List<Anomaly> anomalies,
        List<TimelineEntry> timeline,
        List<IncidentNote> notes,      // oldest first; the newest MAX_NOTES when there are more
        long notesTotal
) {
    public record RootCause(
            Long rootServiceId,
            String rootServiceName,
            Object confidence,
            Object confidenceMethod,
            String suggestion,
            List<Map<String, Object>> candidates   // each with serviceName added
    ) {
    }

    public record ServiceEntry(
            Long serviceId,
            String name,
            ServiceType type,
            Criticality criticality,
            RiskLevel currentRiskLevel,
            Double currentRiskScore,
            Instant riskCalculatedAt
    ) {
    }

    public record TimelineEntry(
            Instant at,
            String type,        // ANOMALY_START | ANOMALY_END | an incident event (CREATED, RESOLVED, NOTE_ADDED, ...)
            Long anomalyId,
            Long serviceId,
            String serviceName,
            String detail,      // readable description
            String by           // username, for NOTE_ADDED and ACKNOWLEDGED
    ) {
    }
}
