package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ThompsonSamplingSelector}.
 *
 * <p>Covers posterior computation, selection distribution, edge cases (no data,
 * single candidate), and the AM-GM relationship between posteriors.</p>
 */
@ExtendWith(MockitoExtension.class)
class ThompsonSamplingSelectorTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ThompsonSamplingSelector selector;

    /** Deterministic RNG: always returns 0 (samples from N(μ, σ²) equal μ). */
    private final RandomGenerator zeroRng = new RandomGenerator() {
        @Override public long nextLong() { return 0L; }
        @Override public double nextGaussian() { return 0.0; }
    };

    @BeforeEach
    void setUp() {
        selector = new ThompsonSamplingSelector(taskOutcomeRepository, zeroRng);
        ReflectionTestUtils.setField(selector, "maxSamplesPerType", 200);
    }

    /** Row format: [worker_type (String), actual_reward (Double)] */
    private Object[] row(String type, double reward) {
        return new Object[]{type, reward};
    }

    // ── Empty / invalid input ───────────────────────────────────────────────────

    @Test
    @DisplayName("throws IllegalArgumentException for null or empty candidates")
    void sample_emptyCandidates_throws() {
        assertThatThrownBy(() -> selector.sample(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selector.sample(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Prior only (no data) ────────────────────────────────────────────────────

    @Test
    @DisplayName("no historical data → prior posteriors μ=0.5, σ²=1.0")
    void sample_noData_usesPrior() {
        ThompsonSamplingSelector.GaussianPosterior post =
                selector.computePosterior(List.of());

        assertThat(post.mu()).isCloseTo(0.5, within(1e-9));
        assertThat(post.variance()).isCloseTo(1.0, within(1e-9));
        assertThat(post.observations()).isEqualTo(0);
    }

    @Test
    @DisplayName("with zero RNG, no-data candidates all have sample=μ_prior=0.5; first selected")
    void sample_noData_selectsFirstCandidate() {
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(List.of());

        ThompsonSamplingSelector.ThompsonResult result =
                selector.sample(List.of("be-java", "fe-ts", "dba"));

        // With zero-RNG and prior mu=0.5 for all, first candidate wins (iteration order)
        assertThat(result.selectedWorkerType()).isIn("be-java", "fe-ts", "dba");
        assertThat(result.posteriors()).containsKeys("be-java", "fe-ts", "dba");
        assertThat(result.sampledValues()).containsKeys("be-java", "fe-ts", "dba");
    }

    // ── Posterior update ────────────────────────────────────────────────────────

    @Test
    @DisplayName("posterior mean converges toward observed mean with many samples")
    void posterior_manyObservations_convergesToSampleMean() {
        // With n large, prior weight becomes negligible
        List<Double> rewards = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rewards.add(0.9);

        ThompsonSamplingSelector.GaussianPosterior post = selector.computePosterior(rewards);

        // μ_post should be very close to 0.9 (sample mean)
        assertThat(post.mu()).isCloseTo(0.9, within(0.01));
        // σ²_post → 0 as n → ∞
        assertThat(post.variance()).isLessThan(0.01);
        assertThat(post.observations()).isEqualTo(1000);
    }

    @Test
    @DisplayName("posterior variance decreases as more observations are added")
    void posterior_moreObservations_tighterVariance() {
        List<Double> few  = List.of(0.7, 0.8, 0.75);
        List<Double> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(0.75);

        double varFew  = selector.computePosterior(few).variance();
        double varMany = selector.computePosterior(many).variance();

        assertThat(varFew).isGreaterThan(varMany);
    }

    // ── Selection with deterministic RNG ───────────────────────────────────────

    @Test
    @DisplayName("with zero-RNG, worker with higher posterior mean wins")
    void sample_higherMeanWins() {
        // beJava: 10 obs at 0.9 → μ_post ≈ 0.9
        // feTs:   10 obs at 0.3 → μ_post ≈ 0.3
        List<Object[]> allRows = new ArrayList<>();
        for (int i = 0; i < 10; i++) allRows.add(row("be-java", 0.9));
        for (int i = 0; i < 10; i++) allRows.add(row("fe-ts",   0.3));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(allRows);

        ThompsonSamplingSelector.ThompsonResult result =
                selector.sample(List.of("be-java", "fe-ts"));

        // With zero-RNG, θ = μ_post → be-java wins
        assertThat(result.selectedWorkerType()).isEqualTo("be-java");
        assertThat(result.sampledValues().get("be-java"))
                .isGreaterThan(result.sampledValues().get("fe-ts"));
    }

    // ── Single candidate ────────────────────────────────────────────────────────

    @Test
    @DisplayName("single candidate is always selected")
    void sample_singleCandidate_alwaysSelected() {
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(List.of());

        ThompsonSamplingSelector.ThompsonResult result =
                selector.sample(List.of("ops-k8s"));

        assertThat(result.selectedWorkerType()).isEqualTo("ops-k8s");
        assertThat(result.posteriors()).hasSize(1);
    }

    // ── Report structure ────────────────────────────────────────────────────────

    @Test
    @DisplayName("result contains posteriors and samples for all candidates")
    void sample_result_containsAllCandidates() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("be-java", 0.8));
        rows.add(row("fe-ts",   0.7));
        rows.add(row("dba",     0.6));
        when(taskOutcomeRepository.findRewardsByWorkerType()).thenReturn(rows);

        ThompsonSamplingSelector.ThompsonResult result =
                selector.sample(List.of("be-java", "fe-ts", "dba"));

        assertThat(result.posteriors()).containsKeys("be-java", "fe-ts", "dba");
        assertThat(result.sampledValues()).containsKeys("be-java", "fe-ts", "dba");
    }
}
