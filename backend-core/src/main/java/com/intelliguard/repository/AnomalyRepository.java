package com.intelliguard.repository;

import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.DetectorType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface AnomalyRepository extends JpaRepository<Anomaly, Long> {

    // All OPEN episodes of one service (at most one per metric and detector).
    List<Anomaly> findByServiceIdAndStatus(Long serviceId, AnomalyStatus status);

    // Backs GET /api/anomalies - both filters optional, bounded by the Pageable.
    @Query("SELECT a FROM Anomaly a WHERE (:serviceId IS NULL OR a.serviceId = :serviceId) " +
            "AND (:status IS NULL OR a.status = :status) ORDER BY a.detectedAt DESC, a.id DESC")
    List<Anomaly> findFiltered(@Param("serviceId") Long serviceId, @Param("status") AnomalyStatus status, Pageable pageable);

    long countByStatusAndDetector(AnomalyStatus status, DetectorType detector);

    @Transactional
    long deleteByStatusAndEndedAtBefore(AnomalyStatus status, Instant cutoff);
}
