package com.intelliguard.entity;

import com.intelliguard.entity.enums.Difficulty;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.ScenarioType;
import com.intelliguard.entity.enums.SignalType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

// A label for an anomaly the simulator deliberately injected - the evaluation ground truth.
// Kept in its own table, separate from Anomaly (which holds what Phase 4 detection finds), so
// injections can never be mistaken for, or counted as, detections.
@Entity
@Table(name = "ground_truth_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroundTruthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private Long seed;

    @Column(nullable = false)
    private Long serviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignalType signalType;

    // Set for METRIC labels only; SECURITY labels describe security events, not a metric.
    @Enumerated(EnumType.STRING)
    private MetricType metricType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioType scenarioType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty difficulty;

    // Timestamps of the first and last tick that were actually injected (not the planned window).
    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    // Episode id and generator parameters (e.g. sigma shift, level) - shape owned by the simulator.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
