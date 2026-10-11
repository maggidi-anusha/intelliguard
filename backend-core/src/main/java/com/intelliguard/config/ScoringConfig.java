package com.intelliguard.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns on the scheduled jobs (Phase 4.3 RiskScoringJob, Phase 5.3 IncidentCorrelationJob) and
// binds their settings.
@Configuration
@EnableScheduling
@EnableConfigurationProperties({ScoringProperties.class, RiskProperties.class, IncidentProperties.class})
public class ScoringConfig {
}
