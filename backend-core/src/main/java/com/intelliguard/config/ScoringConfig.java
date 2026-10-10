package com.intelliguard.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Turns on the Phase 4.3 scoring job (RiskScoringJob) and binds its settings.
@Configuration
@EnableScheduling
@EnableConfigurationProperties({ScoringProperties.class, RiskProperties.class})
public class ScoringConfig {
}
