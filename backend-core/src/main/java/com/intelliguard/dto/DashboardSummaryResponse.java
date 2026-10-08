package com.intelliguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

// Single aggregation endpoint backing both the summary cards and the per-service health
// badges, so the two views can never disagree - "services" is the one source of truth for
// per-service status, and the counts here are reduced from that same list server-side.
@Getter
@Builder
@AllArgsConstructor
public class DashboardSummaryResponse {
    private int totalServices;
    private int healthyServices;
    private int degradedServices;
    private int unknownServices;
    private long recentLogCount;
    private long recentSecurityEventCount;
    private int windowMinutes;
    private List<ServiceHealthResponse> services;
}
