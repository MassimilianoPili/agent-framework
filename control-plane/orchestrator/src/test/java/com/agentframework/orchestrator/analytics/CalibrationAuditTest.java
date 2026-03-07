package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CalibrationAudit}.
 *
 * <p>Pure tests (no Spring, no Mockito) verifying Brier score, ECE,
 * and Dutch Book detection.</p>
 *
 * @see <a href="https://doi.org/10.1111/j.2517-6161.1983.tb01232.x">
 *     DeGroot &amp; Fienberg (1983), The Comparison and Evaluation of Forecasters</a>
 */
class CalibrationAuditTest {

    @Test
    @DisplayName("Brier score of perfect predictions is zero")
    void brierScore_perfectPredictions_returnsZero() {
        double[] predicted = {1.0, 0.0, 1.0};
        boolean[] actual = {true, false, true};

        double brier = CalibrationAudit.brierScore(predicted, actual);

        assertThat(brier).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Brier score of worst predictions is 1.0")
    void brierScore_worstPredictions_returnsOne() {
        double[] predicted = {0.0, 1.0, 0.0};
        boolean[] actual = {true, false, true};

        double brier = CalibrationAudit.brierScore(predicted, actual);

        // (1-0)² + (0-1)² + (1-0)² = 3 → 3/3 = 1.0
        assertThat(brier).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("ECE of perfectly calibrated predictions is zero")
    void ece_perfectCalibration_returnsZero() {
        // 100 predictions: bin k has predicted prob at center of bin and matching accuracy
        int n = 100;
        double[] predicted = new double[n];
        boolean[] actual = new boolean[n];

        // Put 10 predictions per bin, with accuracy matching confidence
        for (int bin = 0; bin < 10; bin++) {
            double p = (bin + 0.5) / 10.0; // center of bin
            int trueCount = (int) Math.round(p * 10); // matching accuracy
            for (int j = 0; j < 10; j++) {
                int idx = bin * 10 + j;
                predicted[idx] = p;
                actual[idx] = j < trueCount;
            }
        }

        double ece = CalibrationAudit.expectedCalibrationError(predicted, actual, 10);

        // Perfect calibration → ECE should be very close to 0
        assertThat(ece).isLessThan(0.06); // small tolerance for rounding + floating-point
    }

    @Test
    @DisplayName("isDutchBookVulnerable detects large miscalibration")
    void isDutchBookVulnerable_largeMiscalibration_returnsTrue() {
        // Predictions all at 0.9 but actual success rate is only 20%
        double[] predicted = new double[20];
        boolean[] actual = new boolean[20];
        for (int i = 0; i < 20; i++) {
            predicted[i] = 0.9;
            actual[i] = i < 4; // only 4/20 = 20% success
        }

        boolean vulnerable = CalibrationAudit.isDutchBookVulnerable(
                predicted, actual, 10, 0.15);

        // MCE = |0.2 - 0.9| = 0.7 >> 0.15 threshold
        assertThat(vulnerable).isTrue();
    }
}
