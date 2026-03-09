package com.agentframework.orchestrator.budget;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.budget.TaskSplitterService.SplitDecision;
import com.agentframework.orchestrator.config.TaskSplitProperties;
import com.agentframework.orchestrator.config.TaskSplitProperties.ThresholdAction;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TaskSplitterService} — pre-dispatch cost estimation
 * and automatic task splitting (#26L2).
 */
class TaskSplitterServiceTest {

    // ── Default properties (SPLIT action, 50K threshold) ─────────────────────

    private static final TaskSplitProperties SPLIT_PROPS = new TaskSplitProperties(
            true, 50_000, ThresholdAction.SPLIT, 1, 25, 0.5);

    private static final TaskSplitProperties WARN_PROPS = new TaskSplitProperties(
            true, 50_000, ThresholdAction.WARN, 1, 25, 0.5);

    private static final TaskSplitProperties BLOCK_PROPS = new TaskSplitProperties(
            true, 50_000, ThresholdAction.BLOCK, 1, 25, 0.5);

    // ── evaluate tests ───────────────────────────────────────────────────────

    @Test
    void evaluate_belowThreshold_returnsProceed() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        // 100 chars × 25 = 2500 tokens, well below 50K
        PlanItem item = makeItem("BE-001", "x".repeat(100), List.of());

        SplitDecision decision = service.evaluate(item, null);

        assertThat(decision.action()).isEqualTo(SplitDecision.Action.PROCEED);
        assertThat(item.getEstimatedInputTokens()).isEqualTo(2500L);
    }

    @Test
    void evaluate_aboveThreshold_actionSplit_returnsSplit() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        // 2500 chars × 25 = 62500 tokens, above 50K
        PlanItem item = makeItem("BE-001", "x".repeat(2500), List.of());

        SplitDecision decision = service.evaluate(item, null);

        assertThat(decision.action()).isEqualTo(SplitDecision.Action.SPLIT);
        assertThat(decision.estimatedTokens()).isEqualTo(62_500L);
    }

    @Test
    void evaluate_aboveThreshold_actionWarn_returnsWarn() {
        var service = new TaskSplitterService(WARN_PROPS);
        PlanItem item = makeItem("BE-001", "x".repeat(2500), List.of());

        SplitDecision decision = service.evaluate(item, null);

        assertThat(decision.action()).isEqualTo(SplitDecision.Action.WARN);
        assertThat(decision.estimatedTokens()).isEqualTo(62_500L);
    }

    @Test
    void evaluate_aboveThreshold_actionBlock_returnsBlock() {
        var service = new TaskSplitterService(BLOCK_PROPS);
        PlanItem item = makeItem("BE-001", "x".repeat(2500), List.of());

        SplitDecision decision = service.evaluate(item, null);

        assertThat(decision.action()).isEqualTo(SplitDecision.Action.BLOCK);
    }

    @Test
    void evaluate_alreadySplit_returnsProceed() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        PlanItem item = makeItem("BE-001", "x".repeat(5000), List.of());
        item.incrementSplitAttemptCount(); // already split once

        SplitDecision decision = service.evaluate(item, null);

        assertThat(decision.action()).isEqualTo(SplitDecision.Action.PROCEED);
        // estimatedInputTokens NOT set because evaluation skipped
        assertThat(item.getEstimatedInputTokens()).isNull();
    }

    @Test
    void evaluate_gpHighUncertainty_boostsEstimate() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        // 1600 chars × 25 = 40000 tokens — below 50K without GP boost
        PlanItem item = makeItem("BE-001", "x".repeat(1600), List.of());
        GpPrediction highUncertainty = new GpPrediction(0.5, 0.8); // sigma2=0.8 > threshold 0.5

        SplitDecision decision = service.evaluate(item, highUncertainty);

        // 40000 × 1.5 = 60000 > 50K → SPLIT
        assertThat(decision.action()).isEqualTo(SplitDecision.Action.SPLIT);
        assertThat(item.getEstimatedInputTokens()).isEqualTo(60_000L);
    }

    @Test
    void evaluate_gpLowUncertainty_noBoost() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        // 1600 chars × 25 = 40000 tokens — below 50K
        PlanItem item = makeItem("BE-001", "x".repeat(1600), List.of());
        GpPrediction lowUncertainty = new GpPrediction(0.8, 0.3); // sigma2=0.3 < threshold 0.5

        SplitDecision decision = service.evaluate(item, lowUncertainty);

        // 40000, no boost → below 50K → PROCEED
        assertThat(decision.action()).isEqualTo(SplitDecision.Action.PROCEED);
        assertThat(item.getEstimatedInputTokens()).isEqualTo(40_000L);
    }

    // ── estimateInputTokens tests ────────────────────────────────────────────

    @Test
    void estimateInputTokens_dependencyCountBonus() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        // 100 chars × 25 = 2500 + 3 deps × 5000 = 17500
        PlanItem item = makeItem("BE-001", "x".repeat(100), List.of("CM-001", "RM-001", "TM-BE-001"));

        long estimate = service.estimateInputTokens(item, null);

        assertThat(estimate).isEqualTo(2500L + 3 * 5000L); // 17500
    }

    @Test
    void estimateInputTokens_nullDescription_returnsZeroBase() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        PlanItem item = makeItem("BE-001", null, List.of());

        long estimate = service.estimateInputTokens(item, null);

        assertThat(estimate).isZero();
    }

    // ── convertToSubPlan tests ───────────────────────────────────────────────

    @Test
    void convertToSubPlan_mutatesItemCorrectly() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        PlanItem item = makeItem("BE-001", "Build user service", List.of());
        assertThat(item.getWorkerType()).isEqualTo(WorkerType.BE);
        assertThat(item.getSplitAttemptCount()).isZero();

        service.convertToSubPlan(item, Map.of());

        assertThat(item.getWorkerType()).isEqualTo(WorkerType.SUB_PLAN);
        assertThat(item.isAwaitCompletion()).isTrue();
        assertThat(item.getSplitAttemptCount()).isEqualTo(1);
        assertThat(item.getSubPlanSpec()).isNotBlank();
    }

    // ── buildSplitSpec tests ─────────────────────────────────────────────────

    @Test
    void buildSplitSpec_containsOriginalTaskInfo() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        PlanItem item = makeItem("FE-001", "Build React dashboard component", List.of());

        String spec = service.buildSplitSpec(item, Map.of());

        assertThat(spec).contains("Build React dashboard component");
        assertThat(spec).contains("FE");
        assertThat(spec).contains("Task Decomposition Request");
        assertThat(spec).contains("2-4 smaller");
    }

    @Test
    void buildSplitSpec_includesDependencyResults() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        PlanItem item = makeItem("BE-001", "Implement API", List.of("CM-001"));
        Map<String, String> results = Map.of("CM-001", "Found 3 relevant files: User.java, UserService.java, UserController.java");

        String spec = service.buildSplitSpec(item, results);

        assertThat(spec).contains("CM-001");
        assertThat(spec).contains("Found 3 relevant files");
    }

    @Test
    void buildSplitSpec_truncatesLargeResults() {
        var service = new TaskSplitterService(SPLIT_PROPS);
        PlanItem item = makeItem("BE-001", "Implement API", List.of("CM-001"));
        String largeResult = "x".repeat(5000);
        Map<String, String> results = Map.of("CM-001", largeResult);

        String spec = service.buildSplitSpec(item, results);

        assertThat(spec).contains("[... truncated ...]");
        // The full 5000-char result should NOT be in the spec
        assertThat(spec).doesNotContain("x".repeat(5000));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static PlanItem makeItem(String taskKey, String description, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, "Title " + taskKey,
                description, WorkerType.BE, "be-java", deps, List.of());
    }
}
