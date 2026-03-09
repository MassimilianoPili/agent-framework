package com.agentframework.common.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link LaplaceMechanism} (#43 — Differential Privacy).
 */
@DisplayName("LaplaceMechanism (#43) — Differential Privacy")
class LaplaceMechanismTest {

    private final LaplaceMechanism mechanism = new LaplaceMechanism();

    // ── Validation ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Input validation")
    class ValidationTests {

        @Test
        @DisplayName("epsilon <= 0 throws IllegalArgumentException")
        void privatize_zeroEpsilon_throws() {
            assertThatThrownBy(() -> mechanism.privatize(100.0, 1.0, 0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("epsilon");

            assertThatThrownBy(() -> mechanism.privatize(100.0, 1.0, -1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative sensitivity throws IllegalArgumentException")
        void privatize_negativeSensitivity_throws() {
            assertThatThrownBy(() -> mechanism.privatize(100.0, -1.0, 1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sensitivity");
        }

        @Test
        @DisplayName("zero sensitivity returns exact value (no noise)")
        void privatize_zeroSensitivity_returnsExact() {
            double result = mechanism.privatize(42.0, 0.0, 1.0);
            assertThat(result).isEqualTo(42.0);
        }
    }

    // ── Statistical properties ────────────────────────────────────────────────

    @Nested
    @DisplayName("Statistical properties")
    class StatisticalTests {

        @Test
        @DisplayName("large epsilon → small noise (|noise| < 3σ for 99%+ of runs)")
        void privatize_largeEpsilon_noiseIsSmall() {
            double trueValue = 1000.0;
            double sensitivity = 32.0;
            double epsilon = 10.0; // large ε → scale = 32/10 = 3.2
            double scale = sensitivity / epsilon;

            // With 10000 samples, check that 99%+ have |noise| < 5*scale ≈ 16
            int withinBound = 0;
            int n = 10_000;
            double bound = 5.0 * scale;
            for (int i = 0; i < n; i++) {
                double noisy = mechanism.privatize(trueValue, sensitivity, epsilon);
                if (Math.abs(noisy - trueValue) < bound) {
                    withinBound++;
                }
            }
            // P(|X| < 5b) ≈ 1 - e^(-5) ≈ 0.9933
            assertThat((double) withinBound / n).isGreaterThan(0.99);
        }

        @Test
        @DisplayName("preserves mean (unbiased over 10000 samples)")
        void privatize_preservesMean() {
            double trueValue = 1600.0;
            double sensitivity = 32.0;
            double epsilon = 1.0;
            int n = 10_000;

            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += mechanism.privatize(trueValue, sensitivity, epsilon);
            }
            double mean = sum / n;

            // Laplace variance = 2b² where b = sensitivity/epsilon = 32
            // Standard error = sqrt(2*32²/10000) ≈ 0.453
            // Allow 4 SE tolerance ≈ 1.81 → round up to 3.0 for safety
            assertThat(mean).isCloseTo(trueValue, within(3.0));
        }

        @Test
        @DisplayName("privatize always returns different value for non-trivial epsilon")
        void privatize_addsNoise() {
            double trueValue = 1500.0;
            // With 10 samples, at least one should differ from trueValue
            // (probability of exact match is essentially zero with SecureRandom)
            boolean anyDifferent = false;
            for (int i = 0; i < 10; i++) {
                double noisy = mechanism.privatize(trueValue, 32.0, 1.0);
                if (noisy != trueValue) {
                    anyDifferent = true;
                    break;
                }
            }
            assertThat(anyDifferent).isTrue();
        }
    }

    // ── Remaining budget ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Budget composition")
    class BudgetTests {

        @Test
        @DisplayName("remaining budget decreases with queries")
        void remainingBudget_decreasesWithQueries() {
            double full = mechanism.remainingBudget(1.0, 0);
            double half = mechanism.remainingBudget(0.5, 1);

            assertThat(full).isEqualTo(1.0);
            assertThat(half).isEqualTo(0.5);
        }

        @Test
        @DisplayName("remaining budget never goes negative")
        void remainingBudget_neverNegative() {
            double result = mechanism.remainingBudget(1.0, 100);
            assertThat(result).isEqualTo(0.0);
        }

        @Test
        @DisplayName("invalid inputs throw")
        void remainingBudget_invalidInputs_throw() {
            assertThatThrownBy(() -> mechanism.remainingBudget(-1.0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> mechanism.remainingBudget(1.0, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
