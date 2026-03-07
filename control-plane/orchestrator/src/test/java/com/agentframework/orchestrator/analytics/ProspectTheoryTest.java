package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ProspectTheory}.
 *
 * <p>Pure tests (no Spring, no Mockito) verifying the value function,
 * probability weighting, and prospect value computation.</p>
 *
 * @see <a href="https://doi.org/10.2307/1914185">
 *     Kahneman &amp; Tversky (1979), Prospect Theory, Econometrica</a>
 */
class ProspectTheoryTest {

    @Test
    @DisplayName("value of gain returns positive (diminishing sensitivity)")
    void value_gain_returnsPositive() {
        double v = ProspectTheory.value(100.0);

        // v(100) = 100^0.88 ≈ 57.54
        assertThat(v).isGreaterThan(0.0);
        assertThat(v).isCloseTo(Math.pow(100.0, 0.88), within(1e-10));
    }

    @Test
    @DisplayName("value of loss is negative and scaled by lambda")
    void value_loss_returnsNegativeScaledByLambda() {
        double v = ProspectTheory.value(-100.0);

        // v(-100) = -2.25 × 100^0.88 ≈ -129.47
        assertThat(v).isLessThan(0.0);
        assertThat(v).isCloseTo(-2.25 * Math.pow(100.0, 0.88), within(1e-10));

        // |v(-100)| > v(100) due to loss aversion (lambda = 2.25)
        assertThat(Math.abs(v)).isGreaterThan(ProspectTheory.value(100.0));
    }

    @Test
    @DisplayName("value of zero returns zero")
    void value_zero_returnsZero() {
        assertThat(ProspectTheory.value(0.0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("probability weighting at extremes returns 0 or 1")
    void weightProbability_extremes_returnsZeroOrOne() {
        assertThat(ProspectTheory.weightProbability(0.0, 0.61)).isEqualTo(0.0);
        assertThat(ProspectTheory.weightProbability(1.0, 0.61)).isEqualTo(1.0);

        // Small probability should be overweighted: w(0.05) > 0.05
        double wSmall = ProspectTheory.weightProbability(0.05, 0.61);
        assertThat(wSmall).isGreaterThan(0.05);

        // Large probability should be underweighted: w(0.95) < 0.95
        double wLarge = ProspectTheory.weightProbability(0.95, 0.61);
        assertThat(wLarge).isLessThan(0.95);
    }

    @Test
    @DisplayName("symmetric gamble has negative prospect value (loss aversion)")
    void prospectValue_symmetricGamble_negativeDueToLossAversion() {
        // 50/50 gamble: win 100 or lose 100
        double[] outcomes = {100.0, -100.0};
        double[] probabilities = {0.5, 0.5};

        double pv = ProspectTheory.prospectValue(outcomes, probabilities);

        // Loss aversion (λ=2.25) makes the symmetric gamble feel negative
        assertThat(pv).isLessThan(0.0);

        // Compare to raw expected value (0.0) — prospect value is strictly worse
        double rawEV = 0.5 * 100.0 + 0.5 * (-100.0);
        assertThat(pv).isLessThan(rawEV);
    }
}
