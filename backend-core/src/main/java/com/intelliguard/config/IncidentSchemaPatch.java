package com.intelliguard.config;

import com.intelliguard.entity.enums.IncidentStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

// Hibernate created incidents.status (Phase 1) with a CHECK constraint listing the enum values of
// that time; ddl-auto=update never widens it, so the Phase 5.3 MERGED status would be rejected on
// an existing database. Re-create the constraint from the current enum. Idempotent, and a no-op
// on a fresh database (the table doesn't exist yet; Hibernate then creates it with every value).
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentSchemaPatch {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void widenStatusConstraint() {
        String values = Arrays.stream(IncidentStatus.values())
                .map(v -> "'" + v.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            Boolean exists = jdbcTemplate.queryForObject("SELECT to_regclass('incidents') IS NOT NULL", Boolean.class);
            if (Boolean.TRUE.equals(exists)) {
                jdbcTemplate.execute("ALTER TABLE incidents DROP CONSTRAINT IF EXISTS incidents_status_check");
                jdbcTemplate.execute("ALTER TABLE incidents ADD CONSTRAINT incidents_status_check CHECK (status IN (" + values + "))");
            }
        } catch (Exception e) {
            log.warn("Could not update incidents.status CHECK constraint: {}", e.getMessage());
        }
    }
}
