package com.intelliguard.config;

import com.intelliguard.repository.AnomalyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

// At startup, close anomaly episodes that are still OPEN but were last flagged longer ago than
// intelliguard.scoring.episode-stale-after (typically: open when the stack was stopped). Without
// this, the scoring job would extend them across the gap once data flows again, which kept old
// incidents alive and made new anomalies merge into them. Their incidents then resolve through
// IncidentCorrelationJob's normal lifecycle. Idempotent.
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleEpisodeCleanup implements ApplicationRunner {

    private final AnomalyRepository anomalyRepository;
    private final ScoringProperties scoring;

    @Override
    public void run(ApplicationArguments args) {
        closeStale(Instant.now());
    }

    int closeStale(Instant now) {
        Instant cutoff = now.minus(scoring.episodeStaleAfter());
        try {
            int closed = anomalyRepository.closeStaleOpenEpisodes(cutoff);
            log.info("Stale episode cleanup: closed {} OPEN anomaly episode(s) last flagged before {}", closed, cutoff);
            return closed;
        } catch (RuntimeException e) {
            log.warn("Stale episode cleanup failed: {}", e.toString());
            return 0;
        }
    }
}
