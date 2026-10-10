package com.intelliguard.service;

import com.intelliguard.dto.DashboardSummaryResponse;
import com.intelliguard.dto.ServiceHealthResponse;
import com.intelliguard.entity.MetricRecord;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.enums.HealthStatus;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.repository.LogRecordRepository;
import com.intelliguard.repository.MetricRecordRepository;
import com.intelliguard.repository.SecurityEventRepository;
import com.intelliguard.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Health status here is a fixed set of threshold rules applied to the latest value per
// metric type - deterministic, NOT a learned model. This is intentionally separate from
// Phase 4's future ML-based anomaly detection; nothing here should be described as
// "AI-detected" in UI copy or docs.
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class DashboardService {

    private static final int WINDOW_MINUTES = 60;

    // Shared with the risk engine (L0Thresholds) so dashboard badges and risk can't disagree.
    private static final double CPU_DEGRADED_THRESHOLD = L0Thresholds.LIMITS.get(MetricType.CPU);
    private static final double MEMORY_DEGRADED_THRESHOLD = L0Thresholds.LIMITS.get(MetricType.MEMORY);
    private static final double DISK_DEGRADED_THRESHOLD = L0Thresholds.LIMITS.get(MetricType.DISK);
    private static final double ERROR_RATE_DEGRADED_THRESHOLD = L0Thresholds.LIMITS.get(MetricType.ERROR_RATE);
    private static final double LATENCY_DEGRADED_THRESHOLD_MS = L0Thresholds.LIMITS.get(MetricType.LATENCY);

    private final ServiceRepository serviceRepository;
    private final MetricRecordRepository metricRecordRepository;
    private final LogRecordRepository logRecordRepository;
    private final SecurityEventRepository securityEventRepository;

    public DashboardSummaryResponse getSummary() {
        List<Service> services = serviceRepository.findAll();

        List<ServiceHealthResponse> serviceHealths = new ArrayList<>();
        int healthy = 0;
        int degraded = 0;
        int unknown = 0;

        for (Service service : services) {
            ServiceHealthResponse health = deriveHealth(service);
            serviceHealths.add(health);
            switch (health.getStatus()) {
                case HEALTHY -> healthy++;
                case DEGRADED -> degraded++;
                case UNKNOWN -> unknown++;
            }
        }

        Instant cutoff = Instant.now().minus(WINDOW_MINUTES, ChronoUnit.MINUTES);

        return DashboardSummaryResponse.builder()
                .totalServices(services.size())
                .healthyServices(healthy)
                .degradedServices(degraded)
                .unknownServices(unknown)
                .recentLogCount(logRecordRepository.countByTimestampAfter(cutoff))
                .recentSecurityEventCount(securityEventRepository.countByTimestampAfter(cutoff))
                .windowMinutes(WINDOW_MINUTES)
                .services(serviceHealths)
                .build();
    }

    private ServiceHealthResponse deriveHealth(Service service) {
        // Reuses the same bounded, already-existing query the per-service detail view
        // uses - no new metric-scanning query is introduced for this endpoint.
        List<MetricRecord> recent =
                metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(service.getId());

        // Records come back newest-first, so the first record seen per type is the latest
        // value (same convention already used in ServiceDetail.jsx on the frontend).
        Map<MetricType, MetricRecord> latestByType = new EnumMap<>(MetricType.class);
        for (MetricRecord m : recent) {
            latestByType.putIfAbsent(m.getMetricType(), m);
        }

        if (latestByType.isEmpty()) {
            return ServiceHealthResponse.builder()
                    .id(service.getId())
                    .name(service.getName())
                    .type(service.getType())
                    .criticality(service.getCriticality())
                    .status(HealthStatus.UNKNOWN)
                    .reasons(List.of("No metrics recorded yet"))
                    .lastMetricAt(null)
                    .build();
        }

        List<String> reasons = new ArrayList<>();
        checkThreshold(latestByType, MetricType.CPU, CPU_DEGRADED_THRESHOLD, "%", reasons);
        checkThreshold(latestByType, MetricType.MEMORY, MEMORY_DEGRADED_THRESHOLD, "%", reasons);
        checkThreshold(latestByType, MetricType.DISK, DISK_DEGRADED_THRESHOLD, "%", reasons);
        checkThreshold(latestByType, MetricType.ERROR_RATE, ERROR_RATE_DEGRADED_THRESHOLD, "%", reasons);
        checkThreshold(latestByType, MetricType.LATENCY, LATENCY_DEGRADED_THRESHOLD_MS, "ms", reasons);

        Instant lastMetricAt = latestByType.values().stream()
                .map(MetricRecord::getTimestamp)
                .max(Instant::compareTo)
                .orElse(null);

        return ServiceHealthResponse.builder()
                .id(service.getId())
                .name(service.getName())
                .type(service.getType())
                .criticality(service.getCriticality())
                .status(reasons.isEmpty() ? HealthStatus.HEALTHY : HealthStatus.DEGRADED)
                .reasons(reasons)
                .lastMetricAt(lastMetricAt)
                .build();
    }

    private void checkThreshold(Map<MetricType, MetricRecord> latestByType, MetricType type,
                                 double threshold, String unit, List<String> reasons) {
        MetricRecord record = latestByType.get(type);
        if (record != null && record.getValue() != null && record.getValue() > threshold) {
            reasons.add(type.name() + " above " + formatNumber(threshold) + unit
                    + " (" + formatNumber(record.getValue()) + unit + ")");
        }
    }

    private String formatNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
