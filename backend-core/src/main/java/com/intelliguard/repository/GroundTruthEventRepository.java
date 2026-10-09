package com.intelliguard.repository;

import com.intelliguard.entity.GroundTruthEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GroundTruthEventRepository extends JpaRepository<GroundTruthEvent, Long> {

    // Both filters optional (null means "any") - same single-query approach as
    // LogRecordRepository.findRecent rather than one derived method per combination.
    @Query("SELECT g FROM GroundTruthEvent g WHERE (:runId IS NULL OR g.runId = :runId) " +
            "AND (:serviceId IS NULL OR g.serviceId = :serviceId) ORDER BY g.startTime, g.id")
    List<GroundTruthEvent> findFiltered(@Param("runId") String runId, @Param("serviceId") Long serviceId);
}
