package com.intelliguard.repository;

import com.intelliguard.entity.MetricRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetricRecordRepository extends JpaRepository<MetricRecord, Long> {
    List<MetricRecord> findTop50ByServiceIdOrderByTimestampDesc(Long serviceId);

    // Newest-first window for the risk scoring job; the Pageable bounds it (window ticks x 6 metrics).
    List<MetricRecord> findByServiceIdOrderByTimestampDesc(Long serviceId, Pageable pageable);
}
