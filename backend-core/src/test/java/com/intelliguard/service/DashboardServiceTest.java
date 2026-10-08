package com.intelliguard.service;

import com.intelliguard.dto.DashboardSummaryResponse;
import com.intelliguard.dto.ServiceHealthResponse;
import com.intelliguard.entity.MetricRecord;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.HealthStatus;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.ServiceType;
import com.intelliguard.repository.LogRecordRepository;
import com.intelliguard.repository.MetricRecordRepository;
import com.intelliguard.repository.SecurityEventRepository;
import com.intelliguard.repository.ServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Unit tests for the Phase 3 dashboard aggregation/health-derivation logic. These verify
// the fixed-threshold rules only (see DashboardService) - the health status here is
// deterministic, NOT an ML prediction, and these tests are unrelated to the future Phase 4
// anomaly-detection work.
//
// NOTE: written against the sandbox's understanding of the project's Spring Boot 4 /
// Mockito setup but NOT executed in this environment - Maven Central is unreachable from
// this sandbox (see the completion report), so `mvn test` could not be run here. Please run
// `mvn test` locally to confirm these pass.
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private MetricRecordRepository metricRecordRepository;
    @Mock
    private LogRecordRepository logRecordRepository;
    @Mock
    private SecurityEventRepository securityEventRepository;

    @InjectMocks
    private DashboardService dashboardService;

    private Service service(long id, String name) {
        return Service.builder()
                .id(id)
                .name(name)
                .type(ServiceType.MICROSERVICE)
                .criticality(Criticality.MEDIUM)
                .build();
    }

    private MetricRecord metric(long serviceId, MetricType type, double value) {
        return MetricRecord.builder()
                .serviceId(serviceId)
                .metricType(type)
                .value(value)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void emptyDatabase_returnsZeroedSummaryWithNoServices() {
        when(serviceRepository.findAll()).thenReturn(List.of());
        when(logRecordRepository.countByTimestampAfter(any())).thenReturn(0L);
        when(securityEventRepository.countByTimestampAfter(any())).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.getTotalServices()).isZero();
        assertThat(summary.getHealthyServices()).isZero();
        assertThat(summary.getDegradedServices()).isZero();
        assertThat(summary.getUnknownServices()).isZero();
        assertThat(summary.getServices()).isEmpty();
    }

    @Test
    void serviceWithNoMetrics_isUnknown() {
        Service svc = service(1L, "order-service");
        when(serviceRepository.findAll()).thenReturn(List.of(svc));
        when(metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(1L)).thenReturn(List.of());
        when(logRecordRepository.countByTimestampAfter(any())).thenReturn(0L);
        when(securityEventRepository.countByTimestampAfter(any())).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.getUnknownServices()).isEqualTo(1);
        ServiceHealthResponse health = summary.getServices().get(0);
        assertThat(health.getStatus()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(health.getLastMetricAt()).isNull();
    }

    @Test
    void serviceUnderAllThresholds_isHealthy() {
        Service svc = service(2L, "api-gateway");
        when(serviceRepository.findAll()).thenReturn(List.of(svc));
        when(metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(2L)).thenReturn(List.of(
                metric(2L, MetricType.CPU, 40.0),
                metric(2L, MetricType.MEMORY, 55.0),
                metric(2L, MetricType.ERROR_RATE, 0.5)
        ));
        when(logRecordRepository.countByTimestampAfter(any())).thenReturn(0L);
        when(securityEventRepository.countByTimestampAfter(any())).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.getHealthyServices()).isEqualTo(1);
        assertThat(summary.getServices().get(0).getStatus()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(summary.getServices().get(0).getReasons()).isEmpty();
    }

    @Test
    void cpuAboveNinety_isDegradedWithReason() {
        Service svc = service(3L, "payments-db");
        when(serviceRepository.findAll()).thenReturn(List.of(svc));
        when(metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(3L)).thenReturn(List.of(
                metric(3L, MetricType.CPU, 95.0)
        ));
        when(logRecordRepository.countByTimestampAfter(any())).thenReturn(0L);
        when(securityEventRepository.countByTimestampAfter(any())).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        ServiceHealthResponse health = summary.getServices().get(0);
        assertThat(summary.getDegradedServices()).isEqualTo(1);
        assertThat(health.getStatus()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(health.getReasons()).anyMatch(r -> r.contains("CPU"));
    }

    @Test
    void multipleServices_aggregateCountsAcrossStatuses() {
        Service healthySvc = service(4L, "auth-service");
        Service degradedSvc = service(5L, "cache");
        Service unknownSvc = service(6L, "new-service");

        when(serviceRepository.findAll()).thenReturn(List.of(healthySvc, degradedSvc, unknownSvc));
        when(metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(4L))
                .thenReturn(List.of(metric(4L, MetricType.CPU, 20.0)));
        when(metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(5L))
                .thenReturn(List.of(metric(5L, MetricType.LATENCY, 800.0)));
        when(metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(6L))
                .thenReturn(List.of());
        when(logRecordRepository.countByTimestampAfter(any())).thenReturn(7L);
        when(securityEventRepository.countByTimestampAfter(any())).thenReturn(2L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.getTotalServices()).isEqualTo(3);
        assertThat(summary.getHealthyServices()).isEqualTo(1);
        assertThat(summary.getDegradedServices()).isEqualTo(1);
        assertThat(summary.getUnknownServices()).isEqualTo(1);
        assertThat(summary.getRecentLogCount()).isEqualTo(7L);
        assertThat(summary.getRecentSecurityEventCount()).isEqualTo(2L);
    }
}
