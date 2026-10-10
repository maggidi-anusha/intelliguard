package com.intelliguard.entity;

import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.SignalType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

// One DETECTED anomaly episode per (service, metric, detector): opened when the detector
// first flags, extended while it keeps flagging, closed after N unflagged scoring cycles.
// Never written from ground truth - injected-anomaly labels live in ground_truth_events.
@Entity
@Table(name = "anomalies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Anomaly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long serviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignalType signalType;

    // Peak score while open. Scale depends on the detector: L1 = ml-service score (0-1,
    // 0.5 = its threshold); L0 = value / threshold (> 1 means violated).
    @Column(nullable = false)
    private Double anomalyScore;

    // Episode start: timestamp of the first flagged metric sample.
    @Column(nullable = false)
    private Instant detectedAt;

    // Short human-readable description of the peak (detector, metric, value/score).
    @Column(columnDefinition = "TEXT")
    private String rawReference;

    // --- Phase 4.3 episode fields (nullable at the DB level so the column additions are safe) ---

    @Enumerated(EnumType.STRING)
    private MetricType metricType;

    @Enumerated(EnumType.STRING)
    private DetectorType detector;

    @Enumerated(EnumType.STRING)
    private AnomalyStatus status;

    // Timestamp of the most recent flagged sample; becomes the end time when the episode closes.
    private Instant lastFlaggedAt;

    // Set when CLOSED (= lastFlaggedAt at that moment); null while OPEN.
    private Instant endedAt;

    // Consecutive scoring cycles without a flag while OPEN; the episode closes at N.
    private Integer unflaggedCycles;
}
