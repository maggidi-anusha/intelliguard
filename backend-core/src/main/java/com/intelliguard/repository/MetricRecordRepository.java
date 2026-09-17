package com.intelliguard.repository;

import com.intelliguard.entity.MetricRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetricRecordRepository extends JpaRepository<MetricRecord, Long> {
    List<MetricRecord> findTop50ByServiceIdOrderByTimestampDesc(Long serviceId);
}
