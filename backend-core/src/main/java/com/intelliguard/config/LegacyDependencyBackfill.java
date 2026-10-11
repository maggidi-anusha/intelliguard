package com.intelliguard.config;

import com.intelliguard.service.ServiceDependencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Phase 5.3 moves the dependency graph to service_dependencies (several edges per service).
// At every startup, copy the legacy single services.depends_on_service_id values into it.
// Idempotent: edges that already exist are skipped. Runs after the schema is up to date.
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyDependencyBackfill implements ApplicationRunner {

    private final ServiceDependencyService dependencyService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int added = dependencyService.copyLegacyDependencies();
            log.info("Legacy dependsOnServiceId backfill: {} edge(s) added to service_dependencies", added);
        } catch (RuntimeException e) {
            log.warn("Legacy dependsOnServiceId backfill failed: {}", e.toString());
        }
    }
}
