package com.intelliguard.risk;

import com.intelliguard.entity.Anomaly;
import com.intelliguard.entity.enums.AnomalyStatus;
import com.intelliguard.entity.enums.DetectorType;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.SignalType;

import java.time.Duration;
import java.time.Instant;

// Turns per-cycle detector flags into anomaly EPISODES instead of one row per tick:
//   flagged, nothing open      -> open a new episode (start = sample time)
//   flagged, episode open      -> extend it (last flagged time, peak score, reset the counter)
//   not flagged, episode open  -> count the miss; close after closeAfter consecutive misses,
//                                 with end time = the last flagged sample
//   not flagged, nothing open  -> nothing
// Pure: mutates/returns entity objects only; the caller persists whatever is returned.
public final class AnomalyEpisodeTracker {

    private AnomalyEpisodeTracker() {
    }

    /**
     * Closes an OPEN episode whose last flagged sample is more than staleAfter before `reference`
     * (the newest sample about to be processed), e.g. one that was open when the stack stopped. Its
     * end is its last flagged time, so an episode is never extended across such a gap; a new flag
     * then starts a new episode. Returns true when it closed the episode.
     */
    public static boolean closeIfStale(Anomaly open, Instant reference, Duration staleAfter) {
        Instant last = open.getLastFlaggedAt() != null ? open.getLastFlaggedAt() : open.getDetectedAt();
        if (last == null || !last.isBefore(reference.minus(staleAfter))) {
            return false;
        }
        open.setStatus(AnomalyStatus.CLOSED);
        open.setEndedAt(last);
        return true;
    }

    /** @return the anomaly to save after this cycle, or null when nothing changed. */
    public static Anomaly apply(Anomaly open, Long serviceId, MetricType metric, DetectorType detector,
                                boolean flagged, double score, Instant sampleTime, int closeAfter, String description) {
        if (flagged) {
            if (open == null) {
                return Anomaly.builder()
                        .serviceId(serviceId)
                        .signalType(SignalType.METRIC)
                        .metricType(metric)
                        .detector(detector)
                        .status(AnomalyStatus.OPEN)
                        .detectedAt(sampleTime)
                        .lastFlaggedAt(sampleTime)
                        .anomalyScore(score)
                        .unflaggedCycles(0)
                        .rawReference(description)
                        .build();
            }
            if (open.getLastFlaggedAt() == null || sampleTime.isAfter(open.getLastFlaggedAt())) {
                open.setLastFlaggedAt(sampleTime);
            }
            open.setUnflaggedCycles(0);
            if (score > open.getAnomalyScore()) {
                open.setAnomalyScore(score);
                open.setRawReference(description);
            }
            return open;
        }

        if (open == null) {
            return null;
        }
        int misses = (open.getUnflaggedCycles() == null ? 0 : open.getUnflaggedCycles()) + 1;
        open.setUnflaggedCycles(misses);
        if (misses >= closeAfter) {
            open.setStatus(AnomalyStatus.CLOSED);
            open.setEndedAt(open.getLastFlaggedAt());
        }
        return open;
    }
}
