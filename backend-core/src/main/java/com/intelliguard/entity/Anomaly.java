package com.intelliguard.entity;

import com.intelliguard.entity.enums.SignalType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

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

    @Column(nullable = false)
    private Double anomalyScore;

    @Column(nullable = false)
    private Instant detectedAt;

    // Pointer/description of what triggered this anomaly (e.g. a MetricRecord id
    // or a short description) - the exact shape is decided when Phase 4 wires up detection.
    @Column(columnDefinition = "TEXT")
    private String rawReference;
}
