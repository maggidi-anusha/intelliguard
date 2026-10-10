package com.intelliguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// intelliguard.risk.* - weights, level cut-offs and normalization caps of the risk formula
// (see RiskCalculator and docs/risk-engine.md), plus how risk rows are persisted and kept.
@ConfigurationProperties(prefix = "intelliguard.risk")
public record RiskProperties(
        Weights weights,
        Levels levels,
        double errorRateCap,         // ERROR logs/minute that count as fully "on" (normalized 1.0)
        double securityEventCap,     // security events in the recent window that count as 1.0
        Duration persistMinInterval, // persist at most this often while the score is unchanged...
        double persistMinDelta,      // ...unless it moved by at least this many points or changed level
        Duration retention           // service risk rows older than this are deleted
) {
    public record Weights(double ml, double l0, double criticality, double errors, double security) {
        public double sum() {
            return ml + l0 + criticality + errors + security;
        }
    }

    // Lower bounds (inclusive) of MEDIUM, HIGH and CRITICAL on the 0-100 scale.
    public record Levels(double medium, double high, double critical) {
    }
}
