package com.intelliguard.risk;

import com.intelliguard.config.RiskProperties;
import com.intelliguard.config.ScoringProperties;
import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.MetricRecord;
import com.intelliguard.entity.RiskScore;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.LogLevel;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.ml.MlScoreResponse;
import com.intelliguard.ml.MlScoringClient;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.LogRecordRepository;
import com.intelliguard.repository.MetricRecordRepository;
import com.intelliguard.repository.RiskScoreRepository;
import com.intelliguard.repository.SecurityEventRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.service.L0Thresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Every cycle (10 s by default, fixed delay so cycles never overlap), for each service:
//   1. fetch its newest metric window; skip if stale, unchanged, or too short ("insufficient data")
//   2. L0: evaluate the Phase 3 thresholds locally (works even when ml-service is down)
//   3. L1: call ml-service /score; on failure skip ML for the rest of the cycle (L0-only fallback)
//   4. update anomaly episodes per (metric, detector) - open / extend / close, never one row per tick
//   5. compute risk (RiskCalculator), persist it throttled, and publish the current snapshot
// Ground truth (ground_truth_events) is never read here - it exists only for offline evaluation.
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskScoringJob {

    private static final int RETENTION_EVERY_N_CYCLES = 60;

    private final ServiceRepository serviceRepository;
    private final MetricRecordRepository metricRecordRepository;
    private final LogRecordRepository logRecordRepository;
    private final SecurityEventRepository securityEventRepository;
    private final AnomalyRepository anomalyRepository;
    private final RiskScoreRepository riskScoreRepository;
    private final MlScoringClient mlScoringClient;
    private final RiskCalculator riskCalculator;
    private final RiskSnapshotStore snapshotStore;
    private final ScoringProperties scoring;
    private final RiskProperties risk;

    private final Map<Long, Instant> lastScoredTick = new ConcurrentHashMap<>();
    private final Map<Long, RiskScore> lastPersisted = new ConcurrentHashMap<>();
    private int cycles;

    @Scheduled(initialDelayString = "${intelliguard.scoring.initial-delay-ms:15000}",
            fixedDelayString = "${intelliguard.scoring.interval-ms:10000}")
    public void scheduledCycle() {
        if (scoring.enabled()) {
            runCycle(Instant.now());
        }
    }

    public void runCycle(Instant now) {
        boolean tryMl = true;
        for (Service service : serviceRepository.findAll()) {
            try {
                tryMl = scoreService(service, now, tryMl);
            } catch (RuntimeException e) {
                log.warn("Risk scoring failed for service {}: {}", service.getId(), e.toString());
            }
        }
        if (cycles++ % RETENTION_EVERY_N_CYCLES == 0) {
            applyRetention(now);
        }
    }

    /** Scores one service; returns whether ml-service should still be tried this cycle. */
    boolean scoreService(Service service, Instant now, boolean tryMl) {
        Long id = service.getId();
        List<MetricRecord> window = metricRecordRepository.findByServiceIdOrderByTimestampDesc(
                id, PageRequest.of(0, scoring.windowTicks() * MetricType.values().length));
        if (window.isEmpty()) {
            publishUnscored(service, now, null, "insufficient data: no metrics yet");
            return tryMl;
        }
        Instant newest = window.get(0).getTimestamp();
        if (newest.isBefore(now.minus(scoring.staleAfter()))) {
            publishUnscored(service, now, newest, "no new data since " + newest);
            return tryMl;
        }
        if (newest.equals(lastScoredTick.get(id))) {
            return tryMl; // nothing new since the last cycle - keep the current snapshot
        }

        Map<MetricType, List<MetricRecord>> byMetric = new EnumMap<>(MetricType.class);
        for (MetricRecord m : window) {
            byMetric.computeIfAbsent(m.getMetricType(), k -> new ArrayList<>()).add(m);
        }
        byMetric.values().forEach(list -> list.sort(Comparator.comparing(MetricRecord::getTimestamp)));
        int longest = byMetric.values().stream().mapToInt(List::size).max().orElse(0);
        if (longest < scoring.minSamplesPerMetric()) {
            publishUnscored(service, now, newest, "insufficient data: " + longest + " samples per metric, need "
                    + scoring.minSamplesPerMetric());
            lastScoredTick.put(id, newest);
            return tryMl;
        }

        // L1 via ml-service (skipped for the rest of the cycle after the first failure).
        Optional<MlScoreResponse> ml = tryMl ? mlScoringClient.score(id, window) : Optional.empty();
        boolean mlScored = ml.isPresent() && !ml.get().insufficientData();
        String mlStatus = ml.isEmpty() ? "unavailable" : (mlScored ? "ok" : "insufficient data");

        Map<String, Anomaly> open = new HashMap<>();
        for (Anomaly a : anomalyRepository.findByServiceIdAndStatus(id, AnomalyStatus.OPEN)) {
            open.put(key(a.getMetricType(), a.getDetector()), a);
        }
        List<Anomaly> changed = new ArrayList<>();
        List<String> l0Violated = new ArrayList<>();
        List<String> l1Flagged = new ArrayList<>();
        int closeAfter = scoring.closeAfterCycles();

        // L0: the latest value of each metric against the Phase 3 thresholds.
        for (Map.Entry<MetricType, List<MetricRecord>> e : byMetric.entrySet()) {
            MetricType metric = e.getKey();
            MetricRecord latest = e.getValue().get(e.getValue().size() - 1);
            Optional<Double> limit = L0Thresholds.limit(metric);
            if (limit.isEmpty()) {
                continue; // NETWORK has no L0 rule
            }
            boolean violated = L0Thresholds.violated(metric, latest.getValue());
            if (violated) {
                l0Violated.add(metric.name());
            }
            addIfChanged(changed, AnomalyEpisodeTracker.apply(open.get(key(metric, DetectorType.L0)), id, metric,
                    DetectorType.L0, violated, latest.getValue() / limit.get(), latest.getTimestamp(), closeAfter,
                    String.format("L0 %s %.2f vs limit %.0f", metric, latest.getValue(), limit.get())));
        }

        // L1: only when ml-service answered; otherwise its open episodes are left untouched.
        if (mlScored) {
            for (Map.Entry<String, MlScoreResponse.MetricScore> e : ml.get().metrics().entrySet()) {
                MetricType metric = MetricType.valueOf(e.getKey());
                MlScoreResponse.MetricScore s = e.getValue();
                if (s.insufficientData() || s.score() == null || !byMetric.containsKey(metric)) {
                    continue;
                }
                if (s.anomalous()) {
                    l1Flagged.add(metric.name());
                }
                List<MetricRecord> series = byMetric.get(metric);
                addIfChanged(changed, AnomalyEpisodeTracker.apply(open.get(key(metric, DetectorType.L1)), id, metric,
                        DetectorType.L1, s.anomalous(), s.score(), series.get(series.size() - 1).getTimestamp(), closeAfter,
                        String.format("L1 %s score %.3f (raw %.2f, threshold %.3f)", metric, s.score(), s.rawScore(),
                                ml.get().threshold())));
            }
        }
        if (!changed.isEmpty()) {
            anomalyRepository.saveAll(changed);
        }

        // Risk.
        Instant since = now.minus(scoring.recentWindow());
        double minutes = Math.max(scoring.recentWindow().toSeconds() / 60.0, 1e-9);
        double errorsPerMinute = logRecordRepository.countByServiceIdAndLevelAndTimestampAfter(id, LogLevel.ERROR, since) / minutes;
        long securityEvents = securityEventRepository.countByServiceIdAndTimestampAfter(id, since);
        RiskCalculator.Result result = riskCalculator.calculate(new RiskCalculator.Inputs(
                mlScored ? ml.get().serviceScore() : null, !l0Violated.isEmpty(), service.getCriticality(),
                errorsPerMinute, securityEvents));

        Map<String, Object> breakdown = new LinkedHashMap<>(result.breakdown());
        breakdown.put("mlStatus", mlStatus);
        breakdown.put("l1FlaggedMetrics", l1Flagged);
        breakdown.put("l0ViolatedMetrics", l0Violated);
        breakdown.put("dataUntil", newest.toString());
        persistThrottled(id, result, breakdown, now);

        snapshotStore.put(new RiskSnapshot(id, service.getName(), service.getCriticality(), result.score(), result.level(),
                result.mlAvailable(), false, result.mlAvailable() ? null : "ml " + mlStatus + " - L0-only fallback",
                now, newest, l1Flagged, l0Violated, breakdown));
        lastScoredTick.put(id, newest);
        return tryMl && ml.isPresent();
    }

    // One row per service per cycle would be ~70k rows/day for 8 services; instead persist only
    // when the level changes, the score moves by >= persistMinDelta, or persistMinInterval passed.
    private void persistThrottled(Long serviceId, RiskCalculator.Result result, Map<String, Object> breakdown, Instant now) {
        RiskScore last = lastPersisted.get(serviceId);
        boolean persist = last == null
                || last.getLevel() != result.level()
                || Math.abs(last.getScore() - result.score()) >= risk.persistMinDelta()
                || last.getCalculatedAt() == null
                || !now.isBefore(last.getCalculatedAt().plus(risk.persistMinInterval()));
        if (persist) {
            RiskScore saved = riskScoreRepository.save(RiskScore.builder()
                    .serviceId(serviceId).score(result.score()).level(result.level()).breakdown(breakdown).build());
            lastPersisted.put(serviceId, saved);
        }
    }

    private void publishUnscored(Service service, Instant now, Instant newest, String reason) {
        snapshotStore.put(new RiskSnapshot(service.getId(), service.getName(), service.getCriticality(), null, null,
                false, true, reason, now, newest, List.of(), List.of(), null));
    }

    private void applyRetention(Instant now) {
        long risks = riskScoreRepository.deleteByServiceIdIsNotNullAndCalculatedAtBefore(now.minus(risk.retention()));
        long anomalies = anomalyRepository.deleteByStatusAndEndedAtBefore(AnomalyStatus.CLOSED, now.minus(scoring.anomalyRetention()));
        if (risks + anomalies > 0) {
            log.info("Retention: deleted {} risk rows and {} closed anomalies", risks, anomalies);
        }
    }

    private static void addIfChanged(List<Anomaly> changed, Anomaly anomaly) {
        if (anomaly != null) {
            changed.add(anomaly);
        }
    }

    private static String key(MetricType metric, DetectorType detector) {
        return metric + "/" + detector;
    }
}
