package com.intelliguard.repository;

import com.intelliguard.entity.Anomaly;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnomalyRepository extends JpaRepository<Anomaly, Long> {
}
