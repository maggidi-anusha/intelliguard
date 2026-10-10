package com.intelliguard.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// risk_scores.incident_id was created NOT NULL (Phase 1). Phase 4.3 makes it optional so a
// risk score can belong to a service instead of an incident. Hibernate's ddl-auto=update adds
// columns but never relaxes an existing constraint, so drop it explicitly. Idempotent, and a
// no-op on a fresh database (the table doesn't exist yet; Hibernate then creates it nullable).
// Runs during context startup, before the scoring job can write a row.
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskSchemaPatch {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void relaxIncidentIdConstraint() {
        try {
            jdbcTemplate.execute("ALTER TABLE IF EXISTS risk_scores ALTER COLUMN incident_id DROP NOT NULL");
        } catch (Exception e) {
            log.warn("Could not relax risk_scores.incident_id NOT NULL: {}", e.getMessage());
        }
    }
}
