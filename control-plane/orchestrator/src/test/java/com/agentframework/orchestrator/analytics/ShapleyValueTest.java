package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ShapleyValue.CoalitionValueFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ShapleyValue}.
 *
 * <p>Verifies exact Shapley computation on classic game theory examples
 * (unanimity game, additive game, gloves game), Monte Carlo convergence,
 * Banzhaf index symmetry, and the efficiency axiom.</p>
 */
class ShapleyValueTest {

    @Test
    @DisplayName("Shapley of unanimity game allocates equally to all essential players")
    void shapleyValue_unanimityGame_allocatesEqually() {
        // Unanimity game: v(S) = 1 iff S = N (all players needed)
        int n = 3;
        CoalitionValueFunction v = coalition -> coalition.size() == n ? 1.0 : 0.0;

        double[] phi = ShapleyValue.shapleyValue(n, v);

        // Each player gets 1/n
        for (int i = 0; i < n; i++) {
            assertThat(phi[i]).isCloseTo(1.0 / n, within(1e-9));
        }
    }

    @Test
    @DisplayName("Shapley with single player returns v({0})")
    void shapleyValue_singlePlayer_getsGrandCoalitionValue() {
        CoalitionValueFunction v = coalition -> coalition.contains(0) ? 5.0 : 0.0;

        double[] phi = ShapleyValue.shapleyValue(1, v);

        assertThat(phi).hasSize(1);
        assertThat(phi[0]).isEqualTo(5.0);
    }

    @Test
    @DisplayName("Shapley on additive game equals each player's individual value")
    void shapleyValue_additiveGame_equalsIndividualValues() {
        // Additive game: v(S) = sum of individual values
        // Player 0: value 3.0, Player 1: value 5.0, Player 2: value 2.0
        double[] individualValues = {3.0, 5.0, 2.0};
        CoalitionValueFunction v = coalition -> {
            double sum = 0.0;
            for (int player : coalition) sum += individualValues[player];
            return sum;
        };

        double[] phi = ShapleyValue.shapleyValue(3, v);

        // In an additive game, Shapley value = individual value
        for (int i = 0; i < 3; i++) {
            assertThat(phi[i]).isCloseTo(individualValues[i], within(1e-9));
        }
    }

    @Test
    @DisplayName("Shapley values sum to grand coalition value (efficiency axiom)")
    void shapleyValue_efficiencyAxiom_sumEqualsGrandCoalition() {
        // Superadditive game: synergy between players
        CoalitionValueFunction v = coalition -> {
            if (coalition.size() == 3) return 10.0;
            if (coalition.size() == 2) return 5.0;
            if (coalition.size() == 1) return 1.0;
            return 0.0;
        };

        double[] phi = ShapleyValue.shapleyValue(3, v);

        double vN = v.value(Set.of(0, 1, 2));
        boolean efficient = ShapleyValue.verifyEfficiency(phi, vN, 1e-9);
        assertThat(efficient).isTrue();

        double sum = Arrays.stream(phi).sum();
        assertThat(sum).isCloseTo(vN, within(1e-9));
    }

    @Test
    @DisplayName("marginal contribution to empty coalition equals v({i})")
    void marginalContribution_emptyCoalition_returnsIndividualValue() {
        CoalitionValueFunction v = coalition -> {
            if (coalition.contains(0)) return coalition.size() * 2.0;
            return coalition.size() * 1.0;
        };

        double mc = ShapleyValue.marginalContribution(0, Set.of(), v);

        // v({0}) - v({}) = 2.0 - 0.0
        assertThat(mc).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Monte Carlo Shapley approximates exact Shapley within tolerance")
    void monteCarloShapley_convergesToExact_withinTolerance() {
        // Use gloves game for a non-trivial test
        // Player 0: left glove, Players 1,2: right gloves
        // v(S) = min(left gloves in S, right gloves in S)
        CoalitionValueFunction v = coalition -> {
            int left = coalition.contains(0) ? 1 : 0;
            int right = 0;
            if (coalition.contains(1)) right++;
            if (coalition.contains(2)) right++;
            return Math.min(left, right);
        };

        double[] exact = ShapleyValue.shapleyValue(3, v);
        double[] mc = ShapleyValue.monteCarloShapley(3, v, 50000, 42L);

        for (int i = 0; i < 3; i++) {
            assertThat(mc[i]).isCloseTo(exact[i], within(0.05));
        }
    }

    @Test
    @DisplayName("Banzhaf index for symmetric game returns equal values for all players")
    void banzhafIndex_symmetricGame_returnsEqualValues() {
        // Symmetric game: v(S) depends only on |S|
        CoalitionValueFunction v = coalition -> coalition.size() >= 2 ? 1.0 : 0.0;

        double[] beta = ShapleyValue.banzhafIndex(3, v);

        // All players are symmetric → equal Banzhaf indices
        assertThat(beta[0]).isCloseTo(beta[1], within(1e-9));
        assertThat(beta[1]).isCloseTo(beta[2], within(1e-9));
    }

    @Test
    @DisplayName("Shapley of gloves game returns classic left/right asymmetric split")
    void shapleyValue_glovesGame_returnsKnownValues() {
        // Classic gloves game: 1 left glove (player 0), 2 right gloves (players 1, 2)
        // v(S) = min(|left gloves in S|, |right gloves in S|)
        // Known Shapley values: phi_L = 2/3, phi_R1 = phi_R2 = 1/6
        CoalitionValueFunction v = coalition -> {
            int left = coalition.contains(0) ? 1 : 0;
            int right = 0;
            if (coalition.contains(1)) right++;
            if (coalition.contains(2)) right++;
            return Math.min(left, right);
        };

        double[] phi = ShapleyValue.shapleyValue(3, v);

        // The scarce resource (left glove) gets more
        assertThat(phi[0]).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(phi[1]).isCloseTo(1.0 / 6, within(1e-9));
        assertThat(phi[2]).isCloseTo(1.0 / 6, within(1e-9));

        // Verify efficiency: sum = v(N) = min(1, 2) = 1
        assertThat(Arrays.stream(phi).sum()).isCloseTo(1.0, within(1e-9));
    }
}
