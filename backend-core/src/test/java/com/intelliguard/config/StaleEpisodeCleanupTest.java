package com.intelliguard.config;

import com.intelliguard.repository.AnomalyRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The startup cleanup closes OPEN episodes last flagged before now - episode-stale-after, is
// idempotent (a second run finds nothing left), and never stops startup on a failure.
class StaleEpisodeCleanupTest {

    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");

    private final AnomalyRepository anomalies = mock(AnomalyRepository.class);
    private final StaleEpisodeCleanup cleanup = new StaleEpisodeCleanup(anomalies, new ScoringProperties(true, 10_000, 72,
            61, 3, "http://ml-service:8000", Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofMinutes(5),
            Duration.ofDays(7), Duration.ofMinutes(5)));

    @Test
    void closesStaleOpenEpisodesOnceAndIsIdempotent() {
        when(anomalies.closeStaleOpenEpisodes(NOW.minus(Duration.ofMinutes(5)))).thenReturn(3, 0);

        assertThat(cleanup.closeStale(NOW)).isEqualTo(3);
        assertThat(cleanup.closeStale(NOW)).isZero();
        verify(anomalies, times(2)).closeStaleOpenEpisodes(NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void aFailureIsLoggedNotThrown() {
        when(anomalies.closeStaleOpenEpisodes(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThat(cleanup.closeStale(NOW)).isZero();
    }
}
