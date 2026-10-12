package com.intelliguard.repository;

import com.intelliguard.entity.Incident;
import com.intelliguard.entity.enums.IncidentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    List<Incident> findByStatusIn(Collection<IncidentStatus> statuses);

    // Backs GET /api/incidents - status optional, newest first, bounded by the Pageable.
    @Query("SELECT i FROM Incident i WHERE (:status IS NULL OR i.status = :status) " +
            "ORDER BY i.startedAt DESC NULLS LAST, i.id DESC")
    List<Incident> findFiltered(@Param("status") IncidentStatus status, Pageable pageable);

    // Retention: RESOLVED incidents resolved before the cutoff, MERGED ones inactive since then.
    @Query("SELECT i.id FROM Incident i WHERE (i.status = com.intelliguard.entity.enums.IncidentStatus.RESOLVED " +
            "AND i.resolvedAt < :cutoff) OR (i.status = com.intelliguard.entity.enums.IncidentStatus.MERGED " +
            "AND i.lastActivityAt < :cutoff)")
    List<Long> findExpiredIds(@Param("cutoff") Instant cutoff);
}
