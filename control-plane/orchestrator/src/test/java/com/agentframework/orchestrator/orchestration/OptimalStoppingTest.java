package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.orchestration.OptimalStopping.StoppingDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link OptimalStopping}.
 *
 * <p>Pure tests (no Spring, no Mockito) verifying the 1/e rule,
 * threshold computation, and acceptance decisions.</p>
 *
 * @see <a href="https://doi.org/10.1214/ss/1177012493">
 *     Ferguson (1989), Who Solved the Secretary Problem?, Statistical Science</a>
 */
class OptimalStoppingTest {

    @Test
    @DisplayName("observationSize with 100 candidates matches 1/e ≈ 36")
    void observationSize_standardCase_matchesOneOverE() {
        int obsSize = OptimalStopping.observationSize(100, OptimalStopping.ONE_OVER_E);

        // floor(100 / e) = floor(36.79) = 36
        assertThat(obsSize).isEqualTo(36);
    }

    @Test
    @DisplayName("threshold returns maximum of observation rewards")
    void threshold_returnsMax() {
        double[] rewards = {0.3, 0.7, 0.5, 0.2};

        double thresh = OptimalStopping.threshold(rewards);

        assertThat(thresh).isEqualTo(0.7);
    }

    @Test
    @DisplayName("shouldAccept returns true when candidate exceeds threshold")
    void shouldAccept_aboveThreshold_returnsTrue() {
        assertThat(OptimalStopping.shouldAccept(0.8, 0.7)).isTrue();
        assertThat(OptimalStopping.shouldAccept(0.7, 0.7)).isFalse(); // must strictly exceed
        assertThat(OptimalStopping.shouldAccept(0.5, 0.7)).isFalse();
    }

    @Test
    @DisplayName("evaluate rejects candidate below observation threshold")
    void evaluate_candidateBelowThreshold_rejects() {
        // 10 historical rewards, observation phase = first 3 (1/e ≈ 0.37 of 10 = 3)
        double[] history = {0.3, 0.8, 0.5, 0.4, 0.6, 0.7, 0.2, 0.9, 0.1, 0.4};
        double candidate = 0.6; // below max of observation phase (0.8)

        StoppingDecision decision = OptimalStopping.evaluate(
                history, candidate, OptimalStopping.ONE_OVER_E);

        assertThat(decision.threshold()).isEqualTo(0.8); // max of first 3: {0.3, 0.8, 0.5}
        assertThat(decision.observationSize()).isEqualTo(3);
        assertThat(decision.shouldAccept()).isFalse();
    }
}
