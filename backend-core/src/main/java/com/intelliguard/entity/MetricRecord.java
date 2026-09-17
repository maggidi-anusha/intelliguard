package com.intelliguard.entity;

import com.intelliguard.entity.enums.MetricType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "metric_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long serviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MetricType metricType;

    @Column(nullable = false)
    private Double value;

    @Column(nullable = false)
    private Instant timestamp;
}
