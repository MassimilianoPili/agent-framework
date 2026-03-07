package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.GoodhartDetector.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link GoodhartDetector}.
 *
 * <p>Verifies detection of regressional, extremal, and causal Goodhart mechanisms,
 * Pearson correlation computation, divergence checking, and health scoring.</p>
 */
@DisplayName("Goodhart Detector — metric health monitoring")
class GoodhartDetectorTest {

    @Test
    void detectRegressional_smallSample_returnsTrue() {
        assertThat(GoodhartDetector.detectRegressional(3, 10)).isTrue();
    }

    @Test
    void detectRegressional_largeSample_returnsFalse() {
        assertThat(GoodhartDetector.detectRegressional(50, 10)).isFalse();
    }

    @Test
    void detectExtremal_outlier_returnsTrue() {
        // value=3.5, mean=0.5, std=0.5 → z = |3.5-0.5|/0.5 = 6.0 > 3.0
        assertThat(GoodhartDetector.detectExtremal(3.5, 0.5, 0.5, 3.0)).isTrue();
    }

    @Test
    void detectExtremal_normalValue_returnsFalse() {
        // value=0.7, mean=0.5, std=0.5 → z = |0.7-0.5|/0.5 = 0.4 < 3.0
        assertThat(GoodhartDetector.detectExtremal(0.7, 0.5, 0.5, 3.0)).isFalse();
    }

    @Test
    void pearsonCorrelation_perfectPositive_returnsOne() {
        double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] y = {1.0, 2.0, 3.0, 4.0, 5.0};
        assertThat(GoodhartDetector.pearsonCorrelation(x, y)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void pearsonCorrelation_perfectNegative_returnsNegativeOne() {
        double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] y = {5.0, 4.0, 3.0, 2.0, 1.0};
        assertThat(GoodhartDetector.pearsonCorrelation(x, y)).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void pearsonCorrelation_constantValues_returnsZero() {
        double[] x = {0.5, 0.5, 0.5, 0.5};
        double[] y = {0.7, 0.8, 0.6, 0.9};
        assertThat(GoodhartDetector.pearsonCorrelation(x, y)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void checkDivergence_lowCorrelation_returnsDivergent() {
        // Proxy and goal are uncorrelated → causal Goodhart
        double[] proxy = {0.8, 0.7, 0.9, 0.6, 0.8};
        double[] goal = {0.3, 0.9, 0.2, 0.8, 0.4};

        DivergenceResult result = GoodhartDetector.checkDivergence(proxy, goal, 0.3);

        assertThat(result.divergent()).isTrue();
        assertThat(result.primaryType()).isEqualTo(GoodhartType.CAUSAL);
        assertThat(result.correlation()).isLessThan(0.3);
    }

    @Test
    void checkDivergence_highCorrelation_returnsNotDivergent() {
        double[] proxy = {0.3, 0.5, 0.7, 0.9, 1.0};
        double[] goal = {0.35, 0.48, 0.72, 0.88, 0.95};

        DivergenceResult result = GoodhartDetector.checkDivergence(proxy, goal, 0.3);

        assertThat(result.divergent()).isFalse();
        assertThat(result.primaryType()).isEqualTo(GoodhartType.NONE);
        assertThat(result.correlation()).isGreaterThan(0.3);
    }

    @Test
    void metricHealthScore_allClear_returnsOne() {
        double score = GoodhartDetector.metricHealthScore(false, false, 1.0);
        assertThat(score).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void metricHealthScore_allRisks_returnsLowScore() {
        double score = GoodhartDetector.metricHealthScore(true, true, 0.0);
        // 1.0 - 0.3 (regressional) - 0.2 (extremal) - 0.5 (zero correlation) = 0.0
        assertThat(score).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void metricHealthScore_highCorrelation_minimalPenalty() {
        double score = GoodhartDetector.metricHealthScore(false, false, 0.9);
        // 1.0 - (1.0 - 0.9) * 0.5 = 1.0 - 0.05 = 0.95
        assertThat(score).isCloseTo(0.95, within(1e-9));
    }
}
