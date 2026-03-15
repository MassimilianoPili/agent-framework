package com.agentframework.orchestrator.analytics.selfrefine;

import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Gate service deciding whether a task should enter self-refine or external review.
 *
 * <p><b>CRITICAL DESIGN CONSTRAINT</b>: Self-refine without external feedback
 * degrades reasoning quality (Huang et al. 2023). This gate is mandatory —
 * tasks MUST pass through it before entering any refinement loop.</p>
 *
 * <h3>Decision logic</h3>
 * <ol>
 *   <li>If the task is already in a Ralph-Loop → {@code SKIP} (avoid recursive loops)</li>
 *   <li>If the flip-rate monitor shows oscillation → {@code EXTERNAL_REVIEW} (abort self-refine)</li>
 *   <li>If the worker type has objective metrics (compile, test, lint) → {@code SELF_REFINE}</li>
 *   <li>Otherwise → {@code EXTERNAL_REVIEW} (reasoning-only tasks cannot self-correct)</li>
 * </ol>
 *
 * <h3>What counts as "objective metrics"</h3>
 * <p>A task has objective metrics when its output can be verified automatically:</p>
 * <ul>
 *   <li>BE/FE: compilation success, test pass rate, lint score</li>
 *   <li>CONTRACT: schema validation, OpenAPI spec compliance</li>
 *   <li>DBA: SQL syntax check, migration dry-run</li>
 *   <li>REVIEW, CONTEXT_MANAGER, RESEARCH_MANAGER: reasoning-only → no objective metrics</li>
 * </ul>
 *
 * @see FlipRateMonitor
 * @see <a href="https://arxiv.org/abs/2310.01798">
 *     Huang et al. (2023) — Large Language Models Cannot Self-Correct Reasoning Yet</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.self-refine", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SelfRefineConfig.class)
public class SelfRefineGateService {

    private static final Logger log = LoggerFactory.getLogger(SelfRefineGateService.class);

    /** The three possible decisions for a task entering the refinement pipeline. */
    public enum RefinementDecision {
        /** Task can self-refine: has objective metrics and is not oscillating. */
        SELF_REFINE,
        /** Task must go to external REVIEW worker: no objective metrics or is oscillating. */
        EXTERNAL_REVIEW,
        /** Skip refinement entirely: task is already in a loop or max iterations reached. */
        SKIP
    }

    /** Detailed gate evaluation result. */
    public record GateResult(
            RefinementDecision decision,
            String reason,
            int currentIteration,
            double flipRate
    ) {}

    private final FlipRateMonitor flipRateMonitor;
    private final SelfRefineConfig config;

    public SelfRefineGateService(FlipRateMonitor flipRateMonitor,
                                  SelfRefineConfig config) {
        this.flipRateMonitor = flipRateMonitor;
        this.config = config;
    }

    /**
     * Evaluates whether a task should enter self-refine, go to external review, or be skipped.
     *
     * @param item       the plan item to evaluate
     * @param workerType the worker type that executed the task
     * @return gate result with decision and reasoning
     */
    public GateResult evaluate(PlanItem item, WorkerType workerType) {
        String taskKey = item.getTaskKey();
        int iterations = flipRateMonitor.iterationCount(taskKey);
        double flipRate = flipRateMonitor.getFlipRate(taskKey);

        // Rule 1: Max iterations reached → SKIP
        if (iterations >= config.maxIterations()) {
            log.info("Self-refine gate: SKIP task={} reason=max_iterations_reached ({})",
                    taskKey, iterations);
            return new GateResult(RefinementDecision.SKIP,
                    "max iterations reached (" + iterations + "/" + config.maxIterations() + ")",
                    iterations, flipRate);
        }

        // Rule 2: Already in Ralph-Loop → SKIP (avoid recursive refinement loops)
        if (isInRalphLoop(item)) {
            log.info("Self-refine gate: SKIP task={} reason=already_in_ralph_loop", taskKey);
            return new GateResult(RefinementDecision.SKIP,
                    "task is already in Ralph-Loop feedback cycle",
                    iterations, flipRate);
        }

        // Rule 3: Flip rate exceeds threshold → EXTERNAL_REVIEW (oscillation detected)
        if (flipRateMonitor.isOscillating(taskKey, config.flipRateThreshold())) {
            log.warn("Self-refine gate: EXTERNAL_REVIEW task={} reason=oscillation (flipRate={} > {})",
                    taskKey, String.format("%.4f", flipRate),
                    String.format("%.4f", config.flipRateThreshold()));
            return new GateResult(RefinementDecision.EXTERNAL_REVIEW,
                    "oscillation detected (flipRate=" + String.format("%.4f", flipRate) + ")",
                    iterations, flipRate);
        }

        // Rule 4: Score trend is negative → EXTERNAL_REVIEW (degrading, not improving)
        double trend = flipRateMonitor.scoreTrend(taskKey);
        if (iterations >= 2 && trend < -0.05) {
            log.info("Self-refine gate: EXTERNAL_REVIEW task={} reason=negative_trend ({})",
                    taskKey, String.format("%.4f", trend));
            return new GateResult(RefinementDecision.EXTERNAL_REVIEW,
                    "quality degrading (trend=" + String.format("%.4f", trend) + ")",
                    iterations, flipRate);
        }

        // Rule 5: Worker type must have objective metrics → SELF_REFINE or EXTERNAL_REVIEW
        if (hasObjectiveMetrics(workerType)) {
            log.debug("Self-refine gate: SELF_REFINE task={} workerType={}", taskKey, workerType);
            return new GateResult(RefinementDecision.SELF_REFINE,
                    "worker type " + workerType + " has objective metrics",
                    iterations, flipRate);
        }

        // Default: reasoning-only task → EXTERNAL_REVIEW
        log.debug("Self-refine gate: EXTERNAL_REVIEW task={} workerType={} reason=no_objective_metrics",
                taskKey, workerType);
        return new GateResult(RefinementDecision.EXTERNAL_REVIEW,
                "worker type " + workerType + " has no objective metrics (reasoning-only)",
                iterations, flipRate);
    }

    /**
     * Checks if the worker type produces verifiable, objective output.
     *
     * <p>Worker types with objective metrics:</p>
     * <ul>
     *   <li>BE, FE: code compilation, test execution, lint results</li>
     *   <li>CONTRACT: schema validation against JSON Schema / OpenAPI</li>
     *   <li>DBA: SQL syntax validation, migration dry-run</li>
     * </ul>
     *
     * <p>Worker types WITHOUT objective metrics (reasoning-only):</p>
     * <ul>
     *   <li>REVIEW: subjective code quality assessment</li>
     *   <li>CONTEXT_MANAGER, RAG_MANAGER: information retrieval (no right/wrong)</li>
     *   <li>RESEARCH_MANAGER: knowledge synthesis</li>
     *   <li>Council types: advisory opinions</li>
     * </ul>
     */
    boolean hasObjectiveMetrics(WorkerType workerType) {
        return config.allowedWorkerTypes().contains(workerType.name());
    }

    /**
     * Checks if the task is already in a Ralph-Loop feedback cycle.
     *
     * <p>Detection: a task is in Ralph-Loop if it has been dispatched more than once
     * (retryCount > 0 indicates re-execution after REVIEW feedback).</p>
     */
    boolean isInRalphLoop(PlanItem item) {
        return item.getRalphLoopCount() > 0;
    }
}
