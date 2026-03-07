package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ValueOfInformation.ExplorationDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ValueOfInformation}.
 *
 * <p>Verifies EVPI computation, EVSI normal-normal form, net VoI,
 * exploration decisions, profile ranking, and Monte Carlo EVPI.</p>
 */
@DisplayName("Value of Information — exploration vs exploitation")
class ValueOfInformationTest {

    @Test
    void evpi_certainOutcome_returnsZero() {
        // All outcomes are the same → no value in knowing the state
        double evpi = ValueOfInformation.evpi(
                new double[]{0.8, 0.8, 0.8},
                new double[]{0.3, 0.4, 0.3});
        assertThat(evpi).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void evpi_twoScenarios_returnsCorrectValue() {
        // Scenario A: outcome 1.0, prob 0.5
        // Scenario B: outcome -0.5, prob 0.5
        // E[payoff] = 0.5 * 1.0 + 0.5 * (-0.5) = 0.25
        // With perfect info: 0.5 * max(0,1.0) + 0.5 * max(0,-0.5) = 0.5
        // EVPI = 0.5 - 0.25 = 0.25
        double evpi = ValueOfInformation.evpi(
                new double[]{1.0, -0.5},
                new double[]{0.5, 0.5});
        assertThat(evpi).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void evsiNormalNormal_highPriorVariance_returnsHighValue() {
        // High prior uncertainty → lots of value in a new observation
        double evsiHigh = ValueOfInformation.evsiNormalNormal(1.0, 0.1);
        double evsiLow = ValueOfInformation.evsiNormalNormal(0.01, 0.1);

        assertThat(evsiHigh).isGreaterThan(evsiLow);
        assertThat(evsiHigh).isPositive();
    }

    @Test
    void evsiNormalNormal_lowPriorVariance_returnsLowValue() {
        // Low prior uncertainty → little value in a new observation
        double evsi = ValueOfInformation.evsiNormalNormal(0.001, 0.1);
        assertThat(evsi).isPositive();
        assertThat(evsi).isLessThan(0.05); // very small
    }

    @Test
    void netVoi_costExceedsEvsi_returnsNegative() {
        double evsi = 0.1;
        double cost = 0.3;
        double net = ValueOfInformation.netVoi(evsi, cost);
        assertThat(net).isNegative();
        assertThat(net).isCloseTo(-0.2, within(1e-9));
    }

    @Test
    void shouldExplore_positiveNetVoi_returnsTrue() {
        // High prior uncertainty, low cost → exploration profitable
        ExplorationDecision decision = ValueOfInformation.shouldExplore(
                0.5, 1.0, 0.1, 0.01);

        assertThat(decision.shouldExplore()).isTrue();
        assertThat(decision.netVoi()).isPositive();
        assertThat(decision.evsi()).isGreaterThan(decision.explorationCost());
    }

    @Test
    void shouldExplore_negativeNetVoi_returnsFalse() {
        // Low prior uncertainty, high cost → exploitation preferred
        ExplorationDecision decision = ValueOfInformation.shouldExplore(
                0.5, 0.001, 0.1, 0.5);

        assertThat(decision.shouldExplore()).isFalse();
        assertThat(decision.netVoi()).isNegative();
    }

    @Test
    void rankByVoi_ordersCorrectly() {
        // Profile 0: low variance (boring), Profile 1: high variance (interesting),
        // Profile 2: medium variance
        double[] priorSigma2s = {0.01, 1.0, 0.1};
        int[] ranking = ValueOfInformation.rankByVoi(priorSigma2s, 0.1, 0.01);

        assertThat(ranking).hasSize(3);
        // Profile 1 (highest variance) should rank first
        assertThat(ranking[0]).isEqualTo(1);
        // Profile 2 (medium) should be second
        assertThat(ranking[1]).isEqualTo(2);
        // Profile 0 (lowest) should be last
        assertThat(ranking[2]).isEqualTo(0);
    }

    @Test
    void evpiMonteCarlo_identicalActions_returnsNearZero() {
        // Two actions with same mean → EVPI ≈ 0 (no benefit from knowing state)
        double evpi = ValueOfInformation.evpiMonteCarlo(
                new double[]{0.5, 0.5},
                new double[]{0.1, 0.1},
                10000, 42);
        assertThat(evpi).isCloseTo(0.0, within(0.01));
    }

    @Test
    void evpiMonteCarlo_differentActions_returnsPositive() {
        // Two actions with different means → EVPI > 0
        double evpi = ValueOfInformation.evpiMonteCarlo(
                new double[]{0.3, 0.7},
                new double[]{0.5, 0.5},
                10000, 42);
        assertThat(evpi).isPositive();
    }
}
