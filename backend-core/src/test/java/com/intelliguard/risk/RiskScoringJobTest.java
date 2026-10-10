package com.intelliguard.risk;

import com.intelliguard.config.RiskProperties;
import com.intelliguard.config.ScoringProperties;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.MetricRecord;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.ServiceType;
import com.intelliguard.ml.MlScoringClient;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.LogRecordRepository;
import com.intelliguard.repository.MetricRecordRepository;
import com.intelliguard.repository.RiskScoreRepository;
import com.intelliguard.repository.SecurityEventRepository;
import com.intelliguard.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// ml-service failure fallback at the job level: with ml-service down the job still scores
// every service on L0 alone (mlAvailable=false), stores L0 anomalies, leaves L1 episodes alone,
// and calls ml-service only once per cycle instead of once per service.
class RiskScoringJobTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:10:00Z");

    private final ServiceRepository services = mock(ServiceRepository.class);
    private final MetricRecordRepository metrics = mock(MetricRecordRepository.class);
    private final LogRecordRepository logs = mock(LogRecordRepository.class);
    private final SecurityEventRepository security = mock(SecurityEventRepository.class);
    private final AnomalyRepository anomalies = mock(AnomalyRepository.class);
    private final RiskScoreRepository risks = mock(RiskScoreRepository.class);
    private final MlScoringClient ml = mock(MlScoringClient.class);
    private final RiskSnapshotStore store = new RiskSnapshotStore();
    private RiskScoringJob job;

    private static Service service(long id, String name) {
        return Service.builder().id(id).name(name).type(ServiceType.MICROSERVICE).criticality(Criticality.HIGH).build();
    }

    private static final java.util.Map<MetricType, Double> NORMAL = java.util.Map.of(MetricType.CPU, 25.0,
            MetricType.MEMORY, 45.0, MetricType.DISK, 35.0, MetricType.NETWORK, 15.0, MetricType.LATENCY, 70.0,
            MetricType.ERROR_RATE, 0.4);

    /** ticks x 6 metrics at normal values, newest first (as the repository returns them);
     *  CPU's latest value = latestCpu. */
    private static List<MetricRecord> window(long serviceId, int ticks, double latestCpu) {
        List<MetricRecord> rows = new ArrayList<>();
        for (int t = 0; t < ticks; t++) {
            Instant ts = NOW.minusSeconds(5 + 5L * t);
            for (MetricType m : MetricType.values()) {
                double v = m == MetricType.CPU && t == 0 ? latestCpu : NORMAL.get(m);
                rows.add(MetricRecord.builder().serviceId(serviceId).metricType(m).value(v).timestamp(ts).build());
            }
        }
        return rows;
    }

    @BeforeEach
    void setUp() {
        ScoringProperties scoring = new ScoringProperties(true, 10_000, 72, 61, 3, "http://ml-service:8000",
                Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofDays(7));
        RiskCalculator calculator = new RiskCalculator(RiskCalculatorTest.props(0.40, 0.20, 0.10, 0.15, 0.15));
        job = new RiskScoringJob(services, metrics, logs, security, anomalies, risks, ml, calculator, store,
                scoring, RiskCalculatorTest.props(0.40, 0.20, 0.10, 0.15, 0.15));
        when(ml.score(anyLong(), any())).thenReturn(Optional.empty()); // ml-service is down
        when(anomalies.findByServiceIdAndStatus(anyLong(), eq(AnomalyStatus.OPEN))).thenReturn(List.of());
        when(risks.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mlDown_scoresOnL0Only_storesTheL0Anomaly_andTriesMlOncePerCycle() {
        when(services.findAll()).thenReturn(List.of(service(4, "auth-service"), service(6, "order-service")));
        when(metrics.findByServiceIdOrderByTimestampDesc(eq(4L), any())).thenReturn(window(4, 70, 20.0));
        when(metrics.findByServiceIdOrderByTimestampDesc(eq(6L), any())).thenReturn(window(6, 70, 97.0));

        job.runCycle(NOW);

        verify(ml, times(1)).score(anyLong(), any()); // skipped for order-service after the first failure
        RiskSnapshot order = store.get(6L).orElseThrow();
        assertThat(order.mlAvailable()).isFalse();
        assertThat(order.insufficientData()).isFalse();
        assertThat(order.reason()).contains("L0-only");
        assertThat(order.l0ViolatedMetrics()).containsExactly("CPU");
        assertThat(order.breakdown().get("mlStatus")).isEqualTo("unavailable");
        // L0-only: l0 0.2/0.6 + HIGH criticality 0.1/0.6*0.75 = 0.4583 -> 45.8
        assertThat(order.score()).isEqualTo(45.8);
        assertThat(store.get(4L).orElseThrow().mlAvailable()).isFalse();

        ArgumentCaptor<List<Anomaly>> saved = ArgumentCaptor.forClass(List.class);
        verify(anomalies).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(a -> {
            assertThat(a.getServiceId()).isEqualTo(6L);
            assertThat(a.getDetector()).isEqualTo(DetectorType.L0);
            assertThat(a.getMetricType()).isEqualTo(MetricType.CPU);
            assertThat(a.getStatus()).isEqualTo(AnomalyStatus.OPEN);
            assertThat(a.getAnomalyScore()).isCloseTo(97.0 / 90.0, org.assertj.core.data.Offset.offset(1e-9));
        });
    }

    @Test
    void tooLittleHistory_isReportedAsInsufficientDataWithoutCallingMl() {
        when(services.findAll()).thenReturn(List.of(service(4, "auth-service")));
        when(metrics.findByServiceIdOrderByTimestampDesc(eq(4L), any())).thenReturn(window(4, 10, 20.0));

        job.runCycle(NOW);

        RiskSnapshot s = store.get(4L).orElseThrow();
        assertThat(s.insufficientData()).isTrue();
        assertThat(s.reason()).contains("insufficient data");
        assertThat(s.score()).isNull();
        verify(ml, never()).score(anyLong(), any());
        verify(risks, never()).save(any());
    }

    @Test
    void staleData_isNotRescored() {
        when(services.findAll()).thenReturn(List.of(service(4, "auth-service")));
        List<MetricRecord> old = window(4, 70, 20.0);
        old.forEach(m -> m.setTimestamp(m.getTimestamp().minus(Duration.ofMinutes(10))));
        when(metrics.findByServiceIdOrderByTimestampDesc(eq(4L), any())).thenReturn(old);

        job.runCycle(NOW);

        assertThat(store.get(4L).orElseThrow().reason()).startsWith("no new data");
        verify(ml, never()).score(anyLong(), any());
    }
}
