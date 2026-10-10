package com.intelliguard.entity;

import com.intelliguard.entity.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

// A risk score from the transparent weighted formula (RiskCalculator - not ML). Phase 4 scores
// services (serviceId set); Phase 5 will score incidents (incidentId set). Both are optional.
@Entity
@Table(name = "risk_scores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Was NOT NULL before Phase 4.3; RiskSchemaPatch drops that constraint on existing databases.
    private Long incidentId;

    private Long serviceId;

    @Column(nullable = false)
    private Double score;

    @Enumerated(EnumType.STRING)
    private RiskLevel level;

    // Every formula component (raw input, normalized value, weight, contribution) plus
    // mlAvailable - enough for the UI to explain the score without recomputing it.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> breakdown;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant calculatedAt;
}
