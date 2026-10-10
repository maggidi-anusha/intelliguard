package com.intelliguard.repository;

import com.intelliguard.entity.LogRecord;
import com.intelliguard.entity.enums.LogLevel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LogRecordRepository extends JpaRepository<LogRecord, Long> {
    List<LogRecord> findTop50ByServiceIdOrderByTimestampDesc(Long serviceId);

    long countByTimestampAfter(Instant timestamp);

    // Risk engine input: recent ERROR logs for one service.
    long countByServiceIdAndLevelAndTimestampAfter(Long serviceId, LogLevel level, Instant timestamp);

    // Backs the global /api/logs endpoint - serviceId and level are both optional filters,
    // so one flexible query is used instead of four derived-method combinations. Bounded by
    // the caller-supplied Pageable rather than returning the whole table.
    @Query("SELECT l FROM LogRecord l WHERE (:serviceId IS NULL OR l.serviceId = :serviceId) " +
            "AND (:level IS NULL OR l.level = :level) ORDER BY l.timestamp DESC")
    List<LogRecord> findRecent(@Param("serviceId") Long serviceId, @Param("level") LogLevel level, Pageable pageable);
}
