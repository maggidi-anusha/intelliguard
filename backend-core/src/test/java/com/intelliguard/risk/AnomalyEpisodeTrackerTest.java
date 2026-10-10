package com.intelliguard.risk;

import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.MetricType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyEpisodeTrackerTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

    private Anomaly step(Anomaly open, boolean flagged, double score, int secondsAfterT0) {
        return AnomalyEpisodeTracker.apply(open, 4L, MetricType.ERROR_RATE, DetectorType.L1, flagged, score,
                T0.plusSeconds(secondsAfterT0), 3, "desc " + score);
    }

    @Test
    void firstFlagOpensOneEpisode() {
        Anomaly a = step(null, true, 0.7, 0);

        assertThat(a.getStatus()).isEqualTo(AnomalyStatus.OPEN);
        assertThat(a.getDetectedAt()).isEqualTo(T0);
        assertThat(a.getLastFlaggedAt()).isEqualTo(T0);
        assertThat(a.getAnomalyScore()).isEqualTo(0.7);
        assertThat(a.getDetector()).isEqualTo(DetectorType.L1);
        assertThat(a.getMetricType()).isEqualTo(MetricType.ERROR_RATE);
        assertThat(a.getEndedAt()).isNull();
    }

    @Test
    void stayingFlaggedExtendsTheSameEpisodeAndKeepsThePeak() {
        Anomaly a = step(null, true, 0.7, 0);
        assertThat(step(a, true, 0.9, 10)).isSameAs(a);
        step(a, true, 0.6, 20);

        assertThat(a.getDetectedAt()).isEqualTo(T0);
        assertThat(a.getLastFlaggedAt()).isEqualTo(T0.plusSeconds(20));
        assertThat(a.getAnomalyScore()).isEqualTo(0.9);
        assertThat(a.getRawReference()).isEqualTo("desc 0.9");
        assertThat(a.getStatus()).isEqualTo(AnomalyStatus.OPEN);
    }

    @Test
    void closesAfterNConsecutiveUnflaggedCyclesWithEndAtTheLastFlag() {
        Anomaly a = step(null, true, 0.7, 0);
        step(a, true, 0.8, 10);
        step(a, false, 0.2, 20);
        step(a, false, 0.2, 30);
        assertThat(a.getStatus()).isEqualTo(AnomalyStatus.OPEN);

        step(a, false, 0.2, 40);
        assertThat(a.getStatus()).isEqualTo(AnomalyStatus.CLOSED);
        assertThat(a.getEndedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(a.getUnflaggedCycles()).isEqualTo(3);
    }

    @Test
    void aFlagBeforeNMissesResetsTheCounter() {
        Anomaly a = step(null, true, 0.7, 0);
        step(a, false, 0.2, 10);
        step(a, false, 0.2, 20);
        step(a, true, 0.6, 30);
        step(a, false, 0.2, 40);
        step(a, false, 0.2, 50);

        assertThat(a.getStatus()).isEqualTo(AnomalyStatus.OPEN);
        assertThat(a.getUnflaggedCycles()).isEqualTo(2);
        assertThat(a.getLastFlaggedAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void unflaggedWithNothingOpenChangesNothing() {
        assertThat(step(null, false, 0.1, 0)).isNull();
    }
}
