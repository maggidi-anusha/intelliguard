package com.intelliguard.dto;

import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.HealthStatus;
import com.intelliguard.entity.enums.ServiceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

// Per-service slice of the dashboard summary. "status"/"reasons" are derived from fixed
// metric thresholds (see DashboardService) - deterministic rules, not an ML prediction.
@Getter
@Builder
@AllArgsConstructor
public class ServiceHealthResponse {
    private Long id;
    private String name;
    private ServiceType type;
    private Criticality criticality;
    private HealthStatus status;
    private List<String> reasons;
    private Instant lastMetricAt;
}
