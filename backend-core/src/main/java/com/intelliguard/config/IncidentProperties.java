package com.intelliguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// intelliguard.incidents.* - the incident correlation job (IncidentCorrelationJob). It calls
// ml-service with the scoring client's URL, timeout and HTTP/1.1 setup (intelliguard.scoring.ml-*).
@ConfigurationProperties(prefix = "intelliguard.incidents")
public record IncidentProperties(
        boolean enabled,
        long intervalMs,
        Duration lookBack,       // CLOSED anomalies that ended within this window are still correlated
        Duration quietPeriod,    // RESOLVED once every linked anomaly has been closed this long
        Duration retention,      // RESOLVED (and MERGED) incidents older than this are deleted
        String profile,          // ml-service correlation parameter profile
        int maxEpisodes          // cap on episodes sent per call (newest kept)
) {
}
