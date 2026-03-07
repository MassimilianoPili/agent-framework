package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link WassersteinDistance}.
 *
 * <p>Pure tests (no Spring, no Mockito) verifying W₁ distance computation,
 * quantile interpolation, and edge cases.</p>
 *
 * @see <a href="https://doi.org/10.1007/978-3-540-71050-9">
 *     Villani (2009), Optimal Transport: Old and New</a>
 */
class WassersteinDistanceTest {

    @Test
    @DisplayName("W₁ of identical distributions is zero")
    void w1_identicalDistributions_returnsZero() {
        double[] a = {1.0, 2.0, 3.0};
        double[] b = {1.0, 2.0, 3.0};

        assertThat(WassersteinDistance.w1(a, b)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("W₁ of uniformly shifted distributions equals shift magnitude")
    void w1_shiftedDistributions_returnsShiftMagnitude() {
        double[] a = {0.0, 1.0, 2.0};
        double[] b = {1.0, 2.0, 3.0};

        // Each element shifted by 1.0 → average |diff| = 1.0
        assertThat(WassersteinDistance.w1(a, b)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("W₁ with different sizes interpolates correctly")
    void w1_differentSizes_interpolatesCorrectly() {
        double[] a = {0.0, 1.0, 2.0, 3.0, 4.0};
        double[] b = {0.0, 4.0};

        // b interpolated to 5 points: [0.0, 1.0, 2.0, 3.0, 4.0] → W₁ = 0
        double w = WassersteinDistance.w1(a, b);
        assertThat(w).isCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("W₁ of single-element arrays equals absolute difference")
    void w1_singleElement_returnsAbsDifference() {
        double[] a = {5.0};
        double[] b = {8.0};

        assertThat(WassersteinDistance.w1(a, b)).isCloseTo(3.0, within(1e-12));
    }

    @Test
    @DisplayName("interpolateQuantiles produces correct intermediate values")
    void interpolateQuantiles_correctInterpolation() {
        double[] sorted = {0.0, 10.0};
        double[] result = WassersteinDistance.interpolateQuantiles(sorted, 5);

        // Expected: [0.0, 2.5, 5.0, 7.5, 10.0]
        assertThat(result).hasSize(5);
        assertThat(result[0]).isCloseTo(0.0, within(1e-12));
        assertThat(result[1]).isCloseTo(2.5, within(1e-12));
        assertThat(result[2]).isCloseTo(5.0, within(1e-12));
        assertThat(result[3]).isCloseTo(7.5, within(1e-12));
        assertThat(result[4]).isCloseTo(10.0, within(1e-12));
    }
}
