package com.intelliguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// intelliguard.scoring.* - the scheduled anomaly/risk scoring job and its ml-service client.
@ConfigurationProperties(prefix = "intelliguard.scoring")
public record ScoringProperties(
        boolean enabled,
        long intervalMs,
        int windowTicks,             // newest ticks fetched per service (x 6 metric rows)
        int minSamplesPerMetric,     // below this the service is skipped as "insufficient data"
        int closeAfterCycles,        // N consecutive unflagged cycles close an open anomaly
        String mlUrl,
        Duration mlTimeout,
        Duration staleAfter,         // newest sample older than this -> no new data, don't score
        Duration recentWindow,       // look-back for error logs and security events
        Duration anomalyRetention    // CLOSED anomalies older than this are deleted
) {
}
