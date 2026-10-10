package com.intelliguard.risk;

import com.intelliguard.config.RiskProperties;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The risk formula is pure arithmetic, so it's tested directly: bounds, that each weight
// contributes exactly weight x normalized, monotonicity, the L0-only fallback and level cut-offs.
class RiskCalculatorTest {

    static RiskProperties props(double ml, double l0, double crit, double errors, double security) {
        return new RiskProperties(new RiskProperties.Weights(ml, l0, crit, errors, security),
                new RiskProperties.Levels(30, 55, 75), 5, 10, Duration.ofSeconds(60), 5, Duration.ofHours(24));
    }

    private final RiskCalculator calc = new RiskCalculator(props(0.40, 0.20, 0.10, 0.15, 0.15));

    private RiskCalculator.Result calc(Double ml, boolean l0, Criticality c, double errors, long security) {
        return calc.calculate(new RiskCalculator.Inputs(ml, l0, c, errors, security));
    }

    @Test
    void everyInputAtMaximum_isExactly100Critical() {
        RiskCalculator.Result r = calc(1.0, true, Criticality.CRITICAL, 5, 10);
        assertThat(r.score()).isEqualTo(100.0);
        assertThat(r.level()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void scoreStaysWithin0And100EvenForOutOfRangeInputs() {
        assertThat(calc(7.5, true, Criticality.CRITICAL, 1e9, 1_000_000).score()).isEqualTo(100.0);
        assertThat(calc(-3.0, false, Criticality.LOW, -50, 0).score()).isBetween(0.0, 100.0);
        assertThat(calc(Double.NaN, false, Criticality.LOW, 0, 0).score()).isBetween(0.0, 100.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachComponentContributesExactlyWeightTimesNormalized() {
        // ml 0.5 -> 0.4*0.5 = 20; l0 -> 20; HIGH -> 0.1*0.75 = 7.5; errors 2.5/min -> 0.15*0.5 = 7.5; 3 events -> 0.15*0.3 = 4.5
        RiskCalculator.Result r = calc(0.5, true, Criticality.HIGH, 2.5, 3);
        assertThat(r.score()).isEqualTo(59.5);
        Map<String, Map<String, Object>> c = (Map<String, Map<String, Object>>) r.breakdown().get("components");
        assertThat(c.get("ml").get("contribution")).isEqualTo(20.0);
        assertThat(c.get("l0").get("contribution")).isEqualTo(20.0);
        assertThat(c.get("criticality").get("contribution")).isEqualTo(7.5);
        assertThat(c.get("errors").get("contribution")).isEqualTo(7.5);
        assertThat(c.get("security").get("contribution")).isEqualTo(4.5);
        assertThat(c.values().stream().mapToDouble(m -> (double) m.get("contribution")).sum()).isEqualTo(59.5);
    }

    @Test
    void scoreNeverDecreasesWhenAnyInputIncreases() {
        double[] mls = {0, 0.2, 0.5, 0.8, 1.0};
        double[] errs = {0, 1, 3, 5, 9};
        long[] secs = {0, 2, 5, 10, 20};
        Criticality[] crits = Criticality.values();
        for (int i = 0; i < mls.length; i++) {
            for (int j = 0; j < errs.length; j++) {
                for (int k = 0; k < secs.length; k++) {
                    for (int c = 0; c < crits.length; c++) {
                        double base = calc(mls[i], false, crits[c], errs[j], secs[k]).score();
                        assertThat(calc(mls[i], true, crits[c], errs[j], secs[k]).score()).isGreaterThanOrEqualTo(base);
                        if (i + 1 < mls.length) assertThat(calc(mls[i + 1], false, crits[c], errs[j], secs[k]).score()).isGreaterThanOrEqualTo(base);
                        if (j + 1 < errs.length) assertThat(calc(mls[i], false, crits[c], errs[j + 1], secs[k]).score()).isGreaterThanOrEqualTo(base);
                        if (k + 1 < secs.length) assertThat(calc(mls[i], false, crits[c], errs[j], secs[k + 1]).score()).isGreaterThanOrEqualTo(base);
                        if (c + 1 < crits.length) assertThat(calc(mls[i], false, crits[c + 1], errs[j], secs[k]).score()).isGreaterThanOrEqualTo(base);
                    }
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutMl_theRemainingWeightsAreRescaledToSumTo1() {
        RiskCalculator.Result r = calc(null, true, Criticality.CRITICAL, 5, 10);
        assertThat(r.mlAvailable()).isFalse();
        assertThat(r.breakdown().get("mlAvailable")).isEqualTo(false);
        assertThat(r.score()).isEqualTo(100.0); // L0-only fallback still reaches the full scale
        Map<String, Map<String, Object>> c = (Map<String, Map<String, Object>>) r.breakdown().get("components");
        assertThat(c.get("ml").get("weight")).isEqualTo(0.0);
        assertThat(c.values().stream().mapToDouble(m -> (double) m.get("weight")).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
        // l0 alone, no ml: 0.2 / 0.6 = one third of the scale
        assertThat(calc(null, true, Criticality.LOW, 0, 0).score()).isEqualTo(Math.round(1000 * (0.2 / 0.6 + 0.1 / 0.6 * 0.25)) / 10.0);
    }

    @Test
    void levelsFollowTheConfiguredCutOffs() {
        assertThat(calc.level(0)).isEqualTo(RiskLevel.LOW);
        assertThat(calc.level(29.9)).isEqualTo(RiskLevel.LOW);
        assertThat(calc.level(30)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(calc.level(54.9)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(calc.level(55)).isEqualTo(RiskLevel.HIGH);
        assertThat(calc.level(75)).isEqualTo(RiskLevel.CRITICAL);
        assertThat(calc.level(100)).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void invalidConfigurationIsRejectedAtStartup() {
        assertThatThrownBy(() -> new RiskCalculator(props(0.5, 0.5, 0.5, 0, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskCalculator(props(1.2, -0.2, 0, 0, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskCalculator(props(1.0, 0, 0, 0, 0))).isInstanceOf(IllegalArgumentException.class);
    }
}
