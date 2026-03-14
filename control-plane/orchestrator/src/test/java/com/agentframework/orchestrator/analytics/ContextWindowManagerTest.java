package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ContextWindowManager.BudgetAllocation;
import com.agentframework.orchestrator.analytics.ContextWindowManager.ContextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ContextWindowManager}.
 */
class ContextWindowManagerTest {

    private ContextWindowManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContextWindowManager();
        ReflectionTestUtils.setField(manager, "compactionThreshold", 0.85);
    }

    // --- Budget computation ---

    @Test
    @DisplayName("computeAvailableBudget subtracts fixed costs")
    void computeBudget_subtractsFixedCosts() {
        int budget = manager.computeAvailableBudget(8000, 1000, 500, 200);
        assertThat(budget).isEqualTo(6300);
    }

    @Test
    @DisplayName("computeAvailableBudget returns 0 when costs exceed max")
    void computeBudget_neverNegative() {
        int budget = manager.computeAvailableBudget(1000, 500, 400, 200);
        assertThat(budget).isZero();
    }

    // --- Greedy allocation ---

    @Test
    @DisplayName("allocate selects chunks by descending density")
    void allocate_selectsByDensity() {
        List<ContextChunk> candidates = List.of(
                new ContextChunk("low-density", "...", 100, 0.1),   // density = 0.001
                new ContextChunk("high-density", "...", 50, 0.9),   // density = 0.018
                new ContextChunk("mid-density", "...", 80, 0.4)     // density = 0.005
        );

        BudgetAllocation result = manager.allocate(candidates, 150);

        // high-density (50) + mid-density (80) = 130 ≤ 150, low-density (100) won't fit
        assertThat(result.selected()).hasSize(2);
        assertThat(result.selected().get(0).label()).isEqualTo("high-density");
        assertThat(result.selected().get(1).label()).isEqualTo("mid-density");
        assertThat(result.budgetUsed()).isEqualTo(130);
    }

    @Test
    @DisplayName("allocate respects budget limit")
    void allocate_respectsBudget() {
        List<ContextChunk> candidates = List.of(
                new ContextChunk("big", "...", 500, 0.9),
                new ContextChunk("small", "...", 100, 0.8)
        );

        BudgetAllocation result = manager.allocate(candidates, 200);

        assertThat(result.selected()).hasSize(1);
        assertThat(result.selected().get(0).label()).isEqualTo("small");
        assertThat(result.budgetUsed()).isEqualTo(100);
    }

    @Test
    @DisplayName("allocate with empty candidates returns empty allocation")
    void allocate_emptyCandidates() {
        BudgetAllocation result = manager.allocate(List.of(), 1000);

        assertThat(result.selected()).isEmpty();
        assertThat(result.budgetUsed()).isZero();
        assertThat(result.utilizationRatio()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("allocate with null candidates returns empty allocation")
    void allocate_nullCandidates() {
        BudgetAllocation result = manager.allocate(null, 1000);

        assertThat(result.selected()).isEmpty();
    }

    @Test
    @DisplayName("allocate with zero budget returns empty allocation")
    void allocate_zeroBudget() {
        List<ContextChunk> candidates = List.of(
                new ContextChunk("chunk", "...", 100, 0.9)
        );

        BudgetAllocation result = manager.allocate(candidates, 0);

        assertThat(result.selected()).isEmpty();
    }

    @Test
    @DisplayName("allocate fills budget completely when possible")
    void allocate_fillsBudget() {
        List<ContextChunk> candidates = List.of(
                new ContextChunk("a", "...", 100, 0.5),
                new ContextChunk("b", "...", 100, 0.5),
                new ContextChunk("c", "...", 100, 0.5)
        );

        BudgetAllocation result = manager.allocate(candidates, 300);

        assertThat(result.selected()).hasSize(3);
        assertThat(result.budgetUsed()).isEqualTo(300);
        assertThat(result.utilizationRatio()).isCloseTo(1.0, within(1e-10));
    }

    // --- Compaction threshold ---

    @Test
    @DisplayName("compactionNeeded is true when utilization exceeds threshold")
    void allocate_compactionNeeded() {
        List<ContextChunk> candidates = List.of(
                new ContextChunk("big", "...", 900, 0.9)
        );

        BudgetAllocation result = manager.allocate(candidates, 1000);

        assertThat(result.utilizationRatio()).isEqualTo(0.9);
        assertThat(result.compactionNeeded()).isTrue(); // 0.9 > 0.85
    }

    @Test
    @DisplayName("compactionNeeded is false when utilization is below threshold")
    void allocate_noCompaction() {
        List<ContextChunk> candidates = List.of(
                new ContextChunk("small", "...", 100, 0.9)
        );

        BudgetAllocation result = manager.allocate(candidates, 1000);

        assertThat(result.utilizationRatio()).isEqualTo(0.1);
        assertThat(result.compactionNeeded()).isFalse();
    }

    // --- Density computation ---

    @Test
    @DisplayName("density is relevance / tokenCount")
    void density_computation() {
        ContextChunk chunk = new ContextChunk("test", "...", 200, 0.8);
        assertThat(ContextWindowManager.density(chunk)).isCloseTo(0.004, within(1e-10));
    }

    @Test
    @DisplayName("density of zero-token chunk is 0.0")
    void density_zeroTokens() {
        ContextChunk chunk = new ContextChunk("empty", "", 0, 0.8);
        assertThat(ContextWindowManager.density(chunk)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("zero-token chunks are filtered out from allocation")
    void allocate_filtersZeroTokenChunks() {
        List<ContextChunk> candidates = List.of(
                new ContextChunk("valid", "...", 100, 0.9),
                new ContextChunk("zero", "", 0, 0.5)
        );

        BudgetAllocation result = manager.allocate(candidates, 500);

        assertThat(result.selected()).hasSize(1);
        assertThat(result.selected().get(0).label()).isEqualTo("valid");
    }
}
