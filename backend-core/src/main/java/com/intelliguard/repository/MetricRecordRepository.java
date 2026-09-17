package com.intelliguard.repository;

import com.intelliguard.entity.MetricRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetricRecordRepository extends JpaRepository<MetricRecord, Long> {
}
