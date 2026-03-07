package com.agentframework.orchestrator.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link KellyCriterion}.
 *
 * <p>Pure tests (no Spring, no Mockito) verifying the Kelly formula,
 * half-Kelly, negative edge handling, and clamping.</p>
 *
 * @see <a href="https://doi.org/10.1002/j.1538-7305.1956.tb03809.x">
 *     Kelly (1956), A New Interpretation of Information Rate,
 *     Bell System Technical Journal</a>
 */
class KellyCriterionTest {

    @Test
    @DisplayName("fullKelly on fair coin returns zero (no edge)")
    void fullKelly_fairCoin_returnsZero() {
        // p=0.5, b=1, a=1 → f* = (0.5×1 - 0.5×1) / (1×1) = 0
        double f = KellyCriterion.fullKelly(0.5, 1.0, 1.0);
        assertThat(f).isEqualTo(0.0);
    }

    @Test
    @DisplayName("fullKelly with positive edge returns positive fraction")
    void fullKelly_edgeCase_returnsPositive() {
        // p=0.6, b=1, a=1 → f* = (0.6×1 - 0.4×1) / (1×1) = 0.2
        double f = KellyCriterion.fullKelly(0.6, 1.0, 1.0);
        assertThat(f).isCloseTo(0.2, within(1e-12));
    }

    @Test
    @DisplayName("halfKelly returns exactly half of fullKelly")
    void halfKelly_halvesFullKelly() {
        double full = KellyCriterion.fullKelly(0.6, 1.0, 1.0);
        double half = KellyCriterion.halfKelly(0.6, 1.0, 1.0);
        assertThat(half).isCloseTo(full / 2.0, within(1e-12));
    }

    @Test
    @DisplayName("fullKelly with negative edge returns zero (don't bet)")
    void fullKelly_negativeEdge_returnsZero() {
        // p=0.3, b=1, a=1 → f* = (0.3×1 - 0.7×1) / (1×1) = -0.4 → clamped to 0
        double f = KellyCriterion.fullKelly(0.3, 1.0, 1.0);
        assertThat(f).isEqualTo(0.0);
    }
}
