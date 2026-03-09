package com.agentframework.orchestrator.budget;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.config.TaskSplitProperties;
import com.agentframework.orchestrator.config.TaskSplitProperties.ThresholdAction;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Pre-dispatch cost estimation gate that triggers automatic task splitting (#26L2).
 *
 * <p>Evaluates each task before dispatch using a heuristic cost estimate
 * (description length × multiplier + dependency bonus, boosted by GP uncertainty).
 * When the estimate exceeds the configured threshold, the task is either warned about,
 * blocked for human approval, or automatically converted to a SUB_PLAN for decomposition.</p>
 *
 * <p>The split mechanism reuses the existing SUB_PLAN infrastructure:
 * {@link PlanItem#forceWorkerType(WorkerType)} converts the domain task in-place,
 * and the existing {@code handleSubPlan()} + {@code onChildPlanCompleted()} lifecycle
 * handles the rest transparently.</p>
 *
 * @see TaskSplitProperties
 * @see TokenBudgetService
 */
@Service
@ConditionalOnProperty(prefix = "task-split", name = "enabled", havingValue = "true")
public class TaskSplitterService {

    private static final Logger log = LoggerFactory.getLogger(TaskSplitterService.class);

    /** Estimated tokens added per dependency (context injection overhead). */
    private static final long DEPENDENCY_TOKEN_BONUS = 5000;

    /** GP uncertainty boost factor applied when sigma² exceeds threshold. */
    private static final double GP_UNCERTAINTY_BOOST = 1.5;

    /** Maximum characters of dependency results included in the split spec. */
    private static final int MAX_RESULT_LENGTH = 2000;

    private final TaskSplitProperties properties;

    public TaskSplitterService(TaskSplitProperties properties) {
        this.properties = properties;
    }

    /**
     * Evaluates whether a task should be split based on estimated cost.
     *
     * <p>Returns {@link SplitDecision#PROCEED} when:
     * <ul>
     *   <li>The estimated tokens are below the threshold</li>
     *   <li>The item has already been split {@code maxSplitAttempts} times</li>
     * </ul>
     *
     * <p>Side effect: sets {@link PlanItem#setEstimatedInputTokens} on the item
     * for post-hoc calibration, regardless of the decision.</p>
     *
     * @param item         the plan item about to be dispatched
     * @param gpPrediction GP prediction for the task (nullable)
     * @return the split decision
     */
    public SplitDecision evaluate(PlanItem item, GpPrediction gpPrediction) {
        if (item.getSplitAttemptCount() >= properties.maxSplitAttempts()) {
            log.debug("Task {} already split {} times (max={}), skipping evaluation",
                      item.getTaskKey(), item.getSplitAttemptCount(), properties.maxSplitAttempts());
            return SplitDecision.PROCEED;
        }

        long estimatedTokens = estimateInputTokens(item, gpPrediction);
        item.setEstimatedInputTokens(estimatedTokens);

        if (estimatedTokens < properties.thresholdTokens()) {
            return SplitDecision.PROCEED;
        }

        return switch (properties.thresholdAction()) {
            case WARN  -> SplitDecision.warn(estimatedTokens);
            case SPLIT -> SplitDecision.split(estimatedTokens);
            case BLOCK -> SplitDecision.block(estimatedTokens);
        };
    }

    /**
     * Estimates input tokens for a task using heuristic signals.
     *
     * <p>Formula: {@code descriptionLength × multiplier + dependencyCount × 5000}.
     * When GP uncertainty ({@code sigma²}) exceeds the threshold, the estimate
     * is boosted by 1.5× to account for unpredictable task complexity.</p>
     *
     * @param item         the plan item
     * @param gpPrediction GP prediction (nullable — no boost without it)
     * @return estimated input tokens
     */
    long estimateInputTokens(PlanItem item, GpPrediction gpPrediction) {
        int descLength = item.getDescription() != null ? item.getDescription().length() : 0;
        long heuristic = (long) descLength * properties.descriptionLengthMultiplier();

        List<String> deps = item.getDependsOn();
        if (deps != null) {
            heuristic += (long) deps.size() * DEPENDENCY_TOKEN_BONUS;
        }

        if (gpPrediction != null && gpPrediction.sigma2() > properties.gpSigma2Threshold()) {
            heuristic = Math.round(heuristic * GP_UNCERTAINTY_BOOST);
        }

        return heuristic;
    }

    /**
     * Converts a domain task into a SUB_PLAN item with a decomposition spec.
     *
     * <p>Mutates the item in-place: sets workerType to SUB_PLAN, populates
     * subPlanSpec with a structured decomposition prompt, sets awaitCompletion=true,
     * and increments the split attempt counter.</p>
     *
     * @param item             the item to convert (mutated)
     * @param completedResults results of completed dependency tasks (for context in spec)
     */
    public void convertToSubPlan(PlanItem item, Map<String, String> completedResults) {
        String subSpec = buildSplitSpec(item, completedResults);
        item.setSubPlanSpec(subSpec);
        item.forceWorkerType(WorkerType.SUB_PLAN);
        item.setAwaitCompletion(true);
        item.incrementSplitAttemptCount();
    }

    /**
     * Builds the decomposition spec for the planner.
     *
     * <p>The spec includes the original task metadata, dependency context (truncated),
     * and explicit constraints so the planner generates 2–4 sub-tasks of the same
     * worker type that are independently executable.</p>
     */
    String buildSplitSpec(PlanItem item, Map<String, String> completedResults) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("## Task Decomposition Request\n\n");
        sb.append("The following task has been identified as too large for a single execution.\n");
        sb.append("Decompose it into 2-4 smaller, independently executable sub-tasks.\n\n");

        sb.append("### Original Task\n\n");
        sb.append("- **Title**: ").append(item.getTitle()).append("\n");
        sb.append("- **Worker Type**: ").append(item.getWorkerType().name()).append("\n");
        if (item.getWorkerProfile() != null) {
            sb.append("- **Worker Profile**: ").append(item.getWorkerProfile()).append("\n");
        }
        if (item.getModelId() != null) {
            sb.append("- **Model**: ").append(item.getModelId()).append("\n");
        }
        sb.append("- **Description**:\n\n").append(item.getDescription()).append("\n\n");

        // Include dependency results as context (truncated)
        List<String> deps = item.getDependsOn();
        if (deps != null && !deps.isEmpty() && completedResults != null && !completedResults.isEmpty()) {
            sb.append("### Available Context (from completed dependencies)\n\n");
            for (String dep : deps) {
                String result = completedResults.get(dep);
                if (result != null) {
                    sb.append("#### ").append(dep).append("\n\n");
                    if (result.length() > MAX_RESULT_LENGTH) {
                        sb.append(result, 0, MAX_RESULT_LENGTH).append("\n[... truncated ...]\n\n");
                    } else {
                        sb.append(result).append("\n\n");
                    }
                }
            }
        }

        sb.append("### Constraints\n\n");
        sb.append("- Each sub-task MUST use worker type: ").append(item.getWorkerType().name()).append("\n");
        sb.append("- Sub-tasks should be independently executable\n");
        sb.append("- Maintain clear dependency ordering between sub-tasks where needed\n");
        sb.append("- Each sub-task should produce a well-defined output\n");

        return sb.toString();
    }

    /** Returns the configured threshold for external callers (post-hoc logging). */
    public long getThresholdTokens() {
        return properties.thresholdTokens();
    }

    // ── Inner type ────────────────────────────────────────────────────────────

    /**
     * Decision returned by {@link #evaluate}: whether to proceed with dispatch,
     * warn and proceed, auto-split, or block for human approval.
     *
     * @param estimatedTokens the heuristic token estimate (0 for PROCEED)
     */
    public record SplitDecision(Action action, long estimatedTokens) {

        public static final SplitDecision PROCEED = new SplitDecision(Action.PROCEED, 0);

        public static SplitDecision warn(long tokens)  { return new SplitDecision(Action.WARN, tokens); }
        public static SplitDecision split(long tokens)  { return new SplitDecision(Action.SPLIT, tokens); }
        public static SplitDecision block(long tokens)  { return new SplitDecision(Action.BLOCK, tokens); }

        public enum Action { PROCEED, WARN, SPLIT, BLOCK }
    }
}
