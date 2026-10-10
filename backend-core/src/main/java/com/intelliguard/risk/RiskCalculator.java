package com.intelliguard.risk;

import com.intelliguard.config.RiskProperties;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// The risk engine: a transparent, hand-weighted formula - NOT machine learning.
//
//   risk = 100 * clamp( sum_i weight_i * normalized_i , 0, 1 )
//
// over five inputs, each normalized to [0, 1] (see docs/risk-engine.md):
//   ml          ml-service service score (0-1; 0.5 = L1 threshold)
//   l0          1 if any Phase 3 threshold is violated, else 0
//   criticality LOW 0.25, MEDIUM 0.5, HIGH 0.75, CRITICAL 1.0
//   errors      ERROR logs per minute / errorRateCap, capped at 1
//   security    security events in the recent window / securityEventCap, capped at 1
// When the ml score is unavailable (ml-service down, or not enough data), the ml term is
// dropped and the remaining weights are rescaled to sum to 1 - an L0-only fallback that stays
// on the same 0-100 scale. Pure: no I/O, no clock, same inputs -> same output.
@Component
public class RiskCalculator {

    private static final double WEIGHT_SUM_TOLERANCE = 1e-6;

    private final RiskProperties properties;

    public RiskCalculator(RiskProperties properties) {
        RiskProperties.Weights w = properties.weights();
        if (w.ml() < 0 || w.l0() < 0 || w.criticality() < 0 || w.errors() < 0 || w.security() < 0) {
            throw new IllegalArgumentException("risk weights must be non-negative: " + w);
        }
        if (Math.abs(w.sum() - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException("risk weights must sum to 1, got " + w.sum());
        }
        if (w.ml() >= 1.0) {
            throw new IllegalArgumentException("ml weight must be < 1 so the L0-only fallback has weight left");
        }
        RiskProperties.Levels l = properties.levels();
        if (!(0 <= l.medium() && l.medium() <= l.high() && l.high() <= l.critical() && l.critical() <= 100)) {
            throw new IllegalArgumentException("risk level cut-offs must satisfy 0 <= medium <= high <= critical <= 100: " + l);
        }
        this.properties = properties;
    }

    public record Inputs(Double mlScore, boolean l0Violated, Criticality criticality,
                         double errorLogsPerMinute, long securityEvents) {
    }

    public record Result(double score, RiskLevel level, boolean mlAvailable, Map<String, Object> breakdown) {
    }

    public Result calculate(Inputs in) {
        RiskProperties.Weights w = properties.weights();
        boolean mlAvailable = in.mlScore() != null;
        // L0-only fallback: drop the ml term and rescale the rest so they still sum to 1.
        double rescale = mlAvailable ? 1.0 : 1.0 / (1.0 - w.ml());

        Map<String, Object> components = new LinkedHashMap<>();
        double sum = 0;
        sum += component(components, "ml", in.mlScore(), mlAvailable ? clamp01(in.mlScore()) : 0.0, mlAvailable ? w.ml() : 0.0);
        sum += component(components, "l0", in.l0Violated(), in.l0Violated() ? 1.0 : 0.0, w.l0() * rescale);
        sum += component(components, "criticality", in.criticality(), criticalityValue(in.criticality()), w.criticality() * rescale);
        sum += component(components, "errors", round(in.errorLogsPerMinute()),
                clamp01(in.errorLogsPerMinute() / properties.errorRateCap()), w.errors() * rescale);
        sum += component(components, "security", in.securityEvents(),
                clamp01(in.securityEvents() / properties.securityEventCap()), w.security() * rescale);

        double score = Math.round(1000.0 * clamp01(sum)) / 10.0; // 0-100, one decimal
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("formula", "risk = 100 * clamp(sum(weight * normalized), 0, 1)");
        breakdown.put("mlAvailable", mlAvailable);
        breakdown.put("components", components);
        return new Result(score, level(score), mlAvailable, breakdown);
    }

    public RiskLevel level(double score) {
        RiskProperties.Levels l = properties.levels();
        if (score >= l.critical()) return RiskLevel.CRITICAL;
        if (score >= l.high()) return RiskLevel.HIGH;
        if (score >= l.medium()) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    static double criticalityValue(Criticality c) {
        if (c == null) return 0.5;
        return switch (c) {
            case LOW -> 0.25;
            case MEDIUM -> 0.5;
            case HIGH -> 0.75;
            case CRITICAL -> 1.0;
        };
    }

    private static double component(Map<String, Object> out, String name, Object input, double normalized, double weight) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("input", input);
        c.put("normalized", round(normalized));
        c.put("weight", round(weight));
        c.put("contribution", round(100.0 * weight * normalized)); // points on the 0-100 scale
        out.put(name, c);
        return weight * normalized;
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
