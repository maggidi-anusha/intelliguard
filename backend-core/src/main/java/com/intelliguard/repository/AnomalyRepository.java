package com.intelliguard.repository;

import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.DetectorType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
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

    // Startup cleanup (StaleEpisodeCleanup): close OPEN episodes last flagged before the cutoff, at
    // their last flagged time. Idempotent: closed rows no longer match.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Anomaly a SET a.status = com.intelliguard.entity.enums.AnomalyStatus.CLOSED, " +
            "a.endedAt = COALESCE(a.lastFlaggedAt, a.detectedAt) " +
            "WHERE a.status = com.intelliguard.entity.enums.AnomalyStatus.OPEN " +
            "AND COALESCE(a.lastFlaggedAt, a.detectedAt) < :cutoff")
    int closeStaleOpenEpisodes(@Param("cutoff") Instant cutoff);

    // --- Phase 5.3 incident correlation ---

    // Correlation input: OPEN episodes, unlinked CLOSED ones that ended at or after the cutoff
    // (look-back), and EVERY episode of an incident that is OPEN/INVESTIGATING or was resolved at or after the
    // cutoff. An incident's members enter and leave the input together: if they aged out one by
    // one, the remaining ones could fall apart into groups and look like a split.
    @Query("SELECT a FROM Anomaly a WHERE a.status = com.intelliguard.entity.enums.AnomalyStatus.OPEN " +
            "OR (a.incidentId IS NULL AND a.status = com.intelliguard.entity.enums.AnomalyStatus.CLOSED AND a.endedAt >= :cutoff) " +
            "OR a.incidentId IN (SELECT i.id FROM Incident i WHERE i.status = com.intelliguard.entity.enums.IncidentStatus.OPEN " +
            "OR i.status = com.intelliguard.entity.enums.IncidentStatus.INVESTIGATING " +
            "OR (i.status = com.intelliguard.entity.enums.IncidentStatus.RESOLVED AND i.resolvedAt >= :cutoff))")
    List<Anomaly> findCorrelationCandidates(@Param("cutoff") Instant cutoff);

    List<Anomaly> findByIncidentIdOrderByDetectedAtAscIdAsc(Long incidentId);

    // incident_id is read-only for JPA (see Anomaly.incidentId); it is only ever written here.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE anomalies SET incident_id = :incidentId WHERE id IN (:ids)", nativeQuery = true)
    int linkToIncident(@Param("incidentId") Long incidentId, @Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE anomalies SET incident_id = NULL WHERE incident_id IN (:incidentIds)", nativeQuery = true)
    int unlinkIncidents(@Param("incidentIds") Collection<Long> incidentIds);
}
