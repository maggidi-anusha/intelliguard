package com.intelliguard.risk;

import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Latest risk for one service, as served by GET /api/risk/current. score/level/breakdown are
// null when the service couldn't be scored (insufficientData + reason say why).
public record RiskSnapshot(
        Long serviceId,
        String serviceName,
        Criticality criticality,
        Double score,
        RiskLevel level,
        boolean mlAvailable,
        boolean insufficientData,
        String reason,
        Instant calculatedAt,
        Instant dataUntil,
        List<String> l1FlaggedMetrics,
        List<String> l0ViolatedMetrics,
        Map<String, Object> breakdown
) {
}
