package com.intelliguard.repository;

import com.intelliguard.entity.RiskScore;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskScoreRepository extends JpaRepository<RiskScore, Long> {
}
