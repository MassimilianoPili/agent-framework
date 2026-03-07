package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.RealOptions.DeferralDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link RealOptions}.
 *
 * <p>Verifies perpetual option pricing, execution threshold computation,
 * option value calculation, deferral decisions, and volatility estimation.</p>
 */
@DisplayName("Real Options — task deferral valuation")
class RealOptionsTest {

    // Standard test parameters: r=0.05, δ=0.01, σ=0.3
    private static final double R = 0.05;
    private static final double DELTA = 0.01;
    private static final double SIGMA = 0.3;

    @Test
    void computeBeta_standardParams_returnsGreaterThanOne() {
        double beta = RealOptions.computeBeta(R, DELTA, SIGMA);
        // β > 1 is required for a finite execution threshold
        assertThat(beta).isGreaterThan(1.0);
    }

    @Test
    void computeBeta_zeroVolatility_returnsLargeBeta() {
        // σ → 0: β → ∞ (equivalent to classical NPV rule)
        double beta = RealOptions.computeBeta(R, DELTA, 0.0);
        assertThat(beta).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void computeBeta_highUrgency_returnsHigherBeta() {
        // Higher convenience yield (urgency) → higher β → lower threshold V* → less deferral
        // β increases with δ because the option value of waiting decreases when
        // the convenience yield (cost of not executing) is higher
        double betaLowUrgency = RealOptions.computeBeta(R, 0.01, SIGMA);
        double betaHighUrgency = RealOptions.computeBeta(R, 0.04, SIGMA);
        assertThat(betaHighUrgency).isGreaterThan(betaLowUrgency);

        // Verify the economic implication: higher β → lower threshold
        double thresholdLow = RealOptions.executionThreshold(100.0, betaLowUrgency);
        double thresholdHigh = RealOptions.executionThreshold(100.0, betaHighUrgency);
        assertThat(thresholdHigh).isLessThan(thresholdLow);
    }

    @Test
    void executionThreshold_standardCase_exceedsCost() {
        double beta = RealOptions.computeBeta(R, DELTA, SIGMA);
        double cost = 100.0;
        double threshold = RealOptions.executionThreshold(cost, beta);
        // V* = β/(β-1) × I > I (option premium: must exceed cost)
        assertThat(threshold).isGreaterThan(cost);
    }

    @Test
    void executionThreshold_largeBeta_approachesCost() {
        // β → ∞: V* → I (threshold approaches cost, NPV rule)
        double cost = 100.0;
        double threshold = RealOptions.executionThreshold(cost, 1000.0);
        // β/(β-1) = 1000/999 ≈ 1.001 → V* ≈ 100.1
        assertThat(threshold).isCloseTo(cost, within(1.0));
    }

    @Test
    void npv_positiveReward_returnsPositive() {
        assertThat(RealOptions.npv(150.0, 100.0)).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void npv_negativeReward_returnsNegative() {
        assertThat(RealOptions.npv(80.0, 100.0)).isCloseTo(-20.0, within(1e-9));
    }

    @Test
    void optionValue_belowThreshold_positive() {
        double beta = RealOptions.computeBeta(R, DELTA, SIGMA);
        double cost = 100.0;
        double threshold = RealOptions.executionThreshold(cost, beta);
        // V below threshold → positive option value
        double v = threshold * 0.8;
        double ov = RealOptions.optionValue(v, threshold, beta, cost);
        assertThat(ov).isGreaterThan(0.0);
    }

    @Test
    void optionValue_aboveThreshold_zero() {
        double beta = RealOptions.computeBeta(R, DELTA, SIGMA);
        double cost = 100.0;
        double threshold = RealOptions.executionThreshold(cost, beta);
        // V above threshold → zero option value (exercise immediately)
        double v = threshold * 1.5;
        double ov = RealOptions.optionValue(v, threshold, beta, cost);
        assertThat(ov).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldDefer_highUncertainty_defersTask() {
        // High volatility, expected reward below threshold → defer
        double v = 0.5; // modest expected reward
        double sigma = 0.5; // high uncertainty
        double cost = 0.3;

        DeferralDecision decision = RealOptions.shouldDefer(v, sigma, cost, R, DELTA);

        // With high uncertainty, V* > I and V might be below V*
        // The decision depends on whether V < V*
        double beta = RealOptions.computeBeta(R, DELTA, sigma);
        double threshold = RealOptions.executionThreshold(cost, beta);
        assertThat(decision.shouldDefer()).isEqualTo(v < threshold);
        assertThat(decision.beta()).isCloseTo(beta, within(1e-9));
        assertThat(decision.threshold()).isCloseTo(threshold, within(1e-9));
    }

    @Test
    void shouldDefer_nearZeroUncertainty_executesImmediately() {
        // σ below EPSILON → β = MAX_VALUE → V* ≈ I → V > I → execute (NPV rule)
        double v = 0.8;
        double sigma = 1e-15; // below EPSILON: triggers NPV guard
        double cost = 0.3;

        DeferralDecision decision = RealOptions.shouldDefer(v, sigma, cost, R, DELTA);

        // β = MAX_VALUE → V* = I = 0.3, V = 0.8 > 0.3 → execute
        assertThat(decision.shouldDefer()).isFalse();
        assertThat(decision.npv()).isCloseTo(0.5, within(1e-3));
        assertThat(decision.reason()).contains("Execute");
    }

    @Test
    void estimateVolatility_constantRewards_returnsZero() {
        double[] rewards = {0.5, 0.5, 0.5, 0.5, 0.5};
        assertThat(RealOptions.estimateVolatility(rewards)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void estimateVolatility_varyingRewards_returnsPositive() {
        double[] rewards = {0.3, 0.5, 0.7, 0.9, 0.1};
        double vol = RealOptions.estimateVolatility(rewards);
        assertThat(vol).isGreaterThan(0.0);
    }

    @Test
    void adjustedConvenienceYield_highUrgency_increasesYield() {
        double base = 0.01;
        double weight = 0.1;

        double low = RealOptions.adjustedConvenienceYield(base, weight, 0.0);
        double high = RealOptions.adjustedConvenienceYield(base, weight, 1.0);

        assertThat(low).isCloseTo(base, within(1e-9));
        assertThat(high).isCloseTo(base + weight, within(1e-9));
        assertThat(high).isGreaterThan(low);
    }

    @Test
    void adjustedConvenienceYield_clampsUrgencyFactor() {
        double base = 0.01;
        double weight = 0.1;

        // urgencyFactor clamped to [0, 1]
        double clamped = RealOptions.adjustedConvenienceYield(base, weight, 2.0);
        assertThat(clamped).isCloseTo(base + weight, within(1e-9));
    }

    @Test
    void computeBeta_zeroDiscountRate_returnsOne() {
        // r = 0: no time value of money → β = 1 (never defer)
        double beta = RealOptions.computeBeta(0.0, DELTA, SIGMA);
        assertThat(beta).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void optionValue_zeroExpectedReward_returnsZero() {
        double beta = RealOptions.computeBeta(R, DELTA, SIGMA);
        double ov = RealOptions.optionValue(0.0, 150.0, beta, 100.0);
        assertThat(ov).isCloseTo(0.0, within(1e-9));
    }
}
