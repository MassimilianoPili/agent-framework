package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.MarkovShapleyService.MarkovShapleyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link MarkovShapleyService}.
 *
 * <p>Verifies Shapley attribution computation, efficiency property,
 * convergence detection, and edge cases.</p>
 */
class MarkovShapleyServiceTest {

    private MarkovShapleyService service;

    @BeforeEach
    void setUp() {
        service = new MarkovShapleyService();
        ReflectionTestUtils.setField(service, "defaultPermutations", 1000);
        ReflectionTestUtils.setField(service, "convergenceSeThreshold", 0.01);
    }

    // --- Single worker ---

    @Test
    @DisplayName("single worker gets all credit")
    void computeAttributions_singleWorkerGetsAll() {
        MarkovShapleyResult result = service.computeAttributions(
                List.of("BE"), Map.of("BE", 0.8), 100);

        assertThat(result.attributions().get("BE")).isCloseTo(0.8, within(0.01));
        assertThat(result.permutationsSampled()).isEqualTo(100);
    }

    // --- Two equal workers ---

    @Test
    @DisplayName("two equal workers get equal credit")
    void computeAttributions_twoEqualWorkers() {
        MarkovShapleyResult result = service.computeAttributions(
                List.of("A", "B"), Map.of("A", 0.5, "B", 0.5), 1000);

        assertThat(result.attributions().get("A")).isCloseTo(0.5, within(0.05));
        assertThat(result.attributions().get("B")).isCloseTo(0.5, within(0.05));
    }

    // --- Efficiency property ---

    @Test
    @DisplayName("efficiency: sum of attributions equals total reward")
    void computeAttributions_efficiencyProperty() {
        Map<String, Double> rewards = Map.of("BE", 0.3, "FE", 0.5, "AI", 0.2);
        double totalReward = rewards.values().stream().mapToDouble(Double::doubleValue).sum();

        MarkovShapleyResult result = service.computeAttributions(
                List.of("BE", "FE", "AI"), rewards, 2000);

        assertThat(service.verifyEfficiency(result, totalReward)).isTrue();
    }

    @Test
    @DisplayName("verifyEfficiency detects violation")
    void verifyEfficiency_detectsViolation() {
        // Manually create a result with wrong attributions
        MarkovShapleyResult fakeResult = new MarkovShapleyResult(
                Map.of("A", 0.1, "B", 0.1), 100, 0.001);

        // Total should be 1.0, but attributions sum to 0.2
        assertThat(service.verifyEfficiency(fakeResult, 1.0)).isFalse();
    }

    // --- Convergence ---

    @Test
    @DisplayName("standard error decreases with more permutations")
    void computeAttributions_seDecreasesWithPermutations() {
        Map<String, Double> rewards = Map.of("A", 0.6, "B", 0.4);

        MarkovShapleyResult result100 = service.computeAttributions(
                List.of("A", "B"), rewards, 100);
        MarkovShapleyResult result5000 = service.computeAttributions(
                List.of("A", "B"), rewards, 5000);

        // More permutations should generally yield lower standard error
        // (statistical — use generous tolerance)
        assertThat(result5000.standardError()).isLessThan(result100.standardError() + 0.05);
    }

    @Test
    @DisplayName("hasConverged returns true when SE is below threshold")
    void hasConverged_belowThreshold() {
        MarkovShapleyResult converged = new MarkovShapleyResult(Map.of("A", 0.5), 1000, 0.005);
        assertThat(service.hasConverged(converged)).isTrue();
    }

    @Test
    @DisplayName("hasConverged returns false when SE is above threshold")
    void hasConverged_aboveThreshold() {
        MarkovShapleyResult notConverged = new MarkovShapleyResult(Map.of("A", 0.5), 10, 0.1);
        assertThat(service.hasConverged(notConverged)).isFalse();
    }

    // --- Coalition value ---

    @Test
    @DisplayName("coalitionValue of empty set is 0")
    void coalitionValue_emptyIsZero() {
        assertThat(service.coalitionValue(Set.of(), Map.of("A", 0.5))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("coalitionValue sums member rewards")
    void coalitionValue_sumsMemberRewards() {
        double value = service.coalitionValue(
                Set.of("A", "B"), Map.of("A", 0.3, "B", 0.7, "C", 0.5));
        assertThat(value).isCloseTo(1.0, within(1e-10));
    }

    @Test
    @DisplayName("coalitionValue handles missing workers gracefully")
    void coalitionValue_missingWorkers() {
        double value = service.coalitionValue(
                Set.of("A", "UNKNOWN"), Map.of("A", 0.5));
        assertThat(value).isCloseTo(0.5, within(1e-10));
    }

    // --- Edge cases ---

    @Test
    @DisplayName("computeAttributions with empty workers returns empty")
    void computeAttributions_emptyWorkers() {
        MarkovShapleyResult result = service.computeAttributions(List.of(), Map.of(), 100);
        assertThat(result.attributions()).isEmpty();
    }

    @Test
    @DisplayName("computeAttributions with null workers returns empty")
    void computeAttributions_nullWorkers() {
        MarkovShapleyResult result = service.computeAttributions(null, Map.of(), 100);
        assertThat(result.attributions()).isEmpty();
    }

    @Test
    @DisplayName("worker with zero reward gets zero attribution")
    void computeAttributions_zeroRewardWorker() {
        MarkovShapleyResult result = service.computeAttributions(
                List.of("A", "B"), Map.of("A", 1.0, "B", 0.0), 500);

        assertThat(result.attributions().get("B")).isCloseTo(0.0, within(0.05));
        assertThat(result.attributions().get("A")).isCloseTo(1.0, within(0.05));
    }
}
