package com.intelliguard.entity;

import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.entity.enums.Severity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// A group of correlated anomaly episodes, maintained by IncidentCorrelationJob (Phase 5.3).
// Anomalies link to it through anomalies.incident_id (each anomaly belongs to at most one
// incident), so no join table is needed. The root cause is a heuristic ranking from ml-service
// POST /correlate, not a learned model (see `method` and docs/incidents.md).
// Columns added in Phase 5.3 are nullable at the DB level so they can be added to an existing table.
@Entity
@Table(name = "incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // e.g. "order-db + 3 related services" (rank-1 candidate first), refreshed while OPEN.
    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    // One-line summary of the rank-1 candidate (service, score, confidence).
    @Column(columnDefinition = "TEXT")
    private String rootCauseSuggestion;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    // --- Phase 5.3 ---

    // Earliest start of its linked anomalies.
    private Instant startedAt;

    // Latest flagged sample of its linked anomalies; the quiet period counts from here.
    private Instant lastActivityAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Long> serviceIds;

    // The rank-1 candidate's service id (also the first entry of rootCause.candidates).
    private Long rootServiceId;

    // ml-service's ranking: {confidence, confidenceMethod, profile, params, candidates: [{serviceId,
    // score, rank, components, evidence}]}. Frozen once RESOLVED.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> rootCause;

    // How the grouping and ranking were produced ("heuristic ... (not a learned model)").
    @Column(columnDefinition = "TEXT")
    private String method;

    // Highest CURRENT risk level among its services (refreshed while OPEN), and the highest seen.
    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    private Severity peakSeverity;

    // Set when status = MERGED: the older incident that absorbed this one.
    private Long mergedIntoId;

    // Lifecycle history: [{at, type: CREATED|MERGED_FROM|MERGED_INTO|SPLIT_FROM|SPLIT_TO|REOPENED|RESOLVED, ...}]
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> events = new ArrayList<>();
}
