package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link HedgeAlgorithm}.
 *
 * <p>Pure tests (no Spring, no Mockito) verifying learning rate computation,
 * multiplicative weight updates, and expert selection.</p>
 *
 * @see <a href="https://doi.org/10.1006/game.1997.0541">
 *     Freund &amp; Schapire (1997), A Decision-Theoretic Generalization of
 *     On-Line Learning, J. Computer and System Sciences</a>
 */
class HedgeAlgorithmTest {

    @Test
    @DisplayName("learningRate matches formula η = √(ln(K)/T)")
    void learningRate_standardCase_matchesFormula() {
        double eta = HedgeAlgorithm.learningRate(3, 100);

        // η = √(ln(3)/100) ≈ 0.1048
        double expected = Math.sqrt(Math.log(3) / 100.0);
        assertThat(eta).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("update with zero losses preserves uniform weights")
    void update_uniformAfterNoLoss_staysUniform() {
        double[] weights = HedgeAlgorithm.uniformWeights(3);
        double[] losses = {0.0, 0.0, 0.0};
        double eta = 0.1;

        double[] updated = HedgeAlgorithm.update(weights, losses, eta);

        // exp(-0.1 * 0) = 1.0 for all experts → weights stay uniform
        assertThat(updated).hasSize(3);
        for (double w : updated) {
            assertThat(w).isCloseTo(1.0 / 3.0, within(1e-12));
        }
    }

    @Test
    @DisplayName("update after high loss reduces expert weight")
    void update_afterHighLoss_reducesWeight() {
        double[] weights = HedgeAlgorithm.uniformWeights(3);
        double[] losses = {0.0, 1.0, 0.0};  // Expert 1 has high loss
        double eta = 0.5;

        double[] updated = HedgeAlgorithm.update(weights, losses, eta);

        // Expert 1 should have lower weight than experts 0 and 2
        assertThat(updated[1]).isLessThan(updated[0]);
        assertThat(updated[1]).isLessThan(updated[2]);

        // Experts 0 and 2 should have equal weights (symmetric)
        assertThat(updated[0]).isCloseTo(updated[2], within(1e-12));

        // Sum should be 1.0 (renormalized)
        double sum = 0;
        for (double w : updated) sum += w;
        assertThat(sum).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("selectExpert returns expert with highest weight after updates")
    void selectExpert_afterUpdates_returnsHighestWeight() {
        double[] weights = HedgeAlgorithm.uniformWeights(4);
        double eta = 0.3;

        // Expert 2 has consistently low loss → should accumulate highest weight
        double[] losses1 = {0.8, 0.6, 0.1, 0.7};
        weights = HedgeAlgorithm.update(weights, losses1, eta);

        double[] losses2 = {0.9, 0.5, 0.05, 0.8};
        weights = HedgeAlgorithm.update(weights, losses2, eta);

        int best = HedgeAlgorithm.selectExpert(weights);
        assertThat(best).isEqualTo(2);

        // Verify weight ordering: expert 2 > all others
        assertThat(weights[2]).isGreaterThan(weights[0]);
        assertThat(weights[2]).isGreaterThan(weights[1]);
        assertThat(weights[2]).isGreaterThan(weights[3]);
    }
}
