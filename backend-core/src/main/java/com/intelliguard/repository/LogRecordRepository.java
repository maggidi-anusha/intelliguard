package com.intelliguard.repository;

import com.intelliguard.entity.LogRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogRecordRepository extends JpaRepository<LogRecord, Long> {
    List<LogRecord> findTop50ByServiceIdOrderByTimestampDesc(Long serviceId);
}
