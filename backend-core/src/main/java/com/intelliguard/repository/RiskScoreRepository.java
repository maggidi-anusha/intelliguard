package com.intelliguard.repository;

import com.intelliguard.entity.RiskScore;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RiskScoreRepository extends JpaRepository<RiskScore, Long> {

    Optional<RiskScore> findTopByServiceIdOrderByCalculatedAtDesc(Long serviceId);

    // Backs GET /api/risk/history - serviceId optional, bounded by the Pageable.
    @Query("SELECT r FROM RiskScore r WHERE r.serviceId IS NOT NULL AND (:serviceId IS NULL OR r.serviceId = :serviceId) " +
            "ORDER BY r.calculatedAt DESC, r.id DESC")
    List<RiskScore> findServiceHistory(@Param("serviceId") Long serviceId, Pageable pageable);

    // Retention: service risk rows only (incident risk, Phase 5, is kept).
    @Transactional
    long deleteByServiceIdIsNotNullAndCalculatedAtBefore(Instant cutoff);
}
