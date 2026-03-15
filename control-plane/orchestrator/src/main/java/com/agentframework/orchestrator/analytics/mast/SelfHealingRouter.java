package com.agentframework.orchestrator.analytics.mast;

import com.agentframework.orchestrator.analytics.mast.MastTaxonomy.FailureClassification;
import com.agentframework.orchestrator.analytics.mast.MastTaxonomy.FailureMode;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes MAST failure classifications to targeted self-healing strategies.
 *
 * <p>Instead of generic retry-with-backoff for all failures, this router maps
 * each failure mode to a specific recovery strategy optimized for that failure type.
 * This dramatically improves recovery success rate compared to uniform retry.</p>
 *
 * <h3>Strategy mapping</h3>
 * <table>
 *   <tr><th>Failure Mode</th><th>Strategy</th><th>Rationale</th></tr>
 *   <tr><td>FM1 Ambiguous</td><td>CLARIFY_SPEC</td><td>Need human input, not retry</td></tr>
 *   <tr><td>FM2 Incomplete</td><td>ENRICH_CONTEXT</td><td>Fetch more context via CONTEXT_MANAGER</td></tr>
 *   <tr><td>FM5 Comm breakdown</td><td>REDISPATCH_WITH_CONTEXT</td><td>Re-run with enriched dependency results</td></tr>
 *   <tr><td>FM6 Deadlock</td><td>BREAK_CYCLE</td><td>Topological sort + skip weakest edge</td></tr>
 *   <tr><td>FM7 Resource</td><td>INCREASE_BUDGET</td><td>Increase token budget or wait for rate limit</td></tr>
 *   <tr><td>FM10 Cascading</td><td>COMPENSATE_SELECTIVE</td><td>Saga compensation on root cause only</td></tr>
 *   <tr><td>FM11 Oscillation</td><td>ABORT_REFINE</td><td>Stop self-refine, escalate to REVIEW</td></tr>
 *   <tr><td>FM13 Partial</td><td>SKIP_AND_RESUME</td><td>Skip non-critical failures, resume plan</td></tr>
 *   <tr><td>FM14 Recovery</td><td>MANUAL_INTERVENTION</td><td>Auto-recovery exhausted, need human</td></tr>
 * </table>
 *
 * @see MastClassifierService
 * @see MastTaxonomy
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.mast", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SelfHealingRouter {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingRouter.class);

    /**
     * A healing action to be executed by the orchestrator.
     *
     * @param strategy     the recovery strategy name
     * @param description  human-readable description of the action
     * @param params       strategy-specific parameters
     * @param requiresHuman whether this action needs human approval before execution
     */
    public record HealingAction(
            String strategy,
            String description,
            Map<String, Object> params,
            boolean requiresHuman
    ) {
        public HealingAction(String strategy, String description, Map<String, Object> params) {
            this(strategy, description, params, false);
        }
    }

    /**
     * Routes a failure classification to the appropriate healing action.
     *
     * @param classification the MAST failure classification
     * @param item           the failed plan item
     * @param plan           the plan context
     * @return the recommended healing action
     */
    public HealingAction route(FailureClassification classification, PlanItem item, Plan plan) {
        FailureMode mode = classification.mode();

        HealingAction action = switch (mode) {
            // FC1: Specification Failures — mostly need human input
            case FM1_AMBIGUOUS_SPEC -> healAmbiguousSpec(item, plan);
            case FM2_INCOMPLETE_REQ -> healIncompleteReq(item, plan);
            case FM3_CONFLICTING_CONSTRAINTS -> healConflictingConstraints(item);
            case FM4_SCOPE_CREEP -> healScopeCreep(item, plan);

            // FC2: Inter-Agent Failures — targeted automated recovery
            case FM5_COMMUNICATION_BREAKDOWN -> healCommunicationBreakdown(item, plan);
            case FM6_COORDINATION_DEADLOCK -> healCoordinationDeadlock(item, plan);
            case FM7_RESOURCE_CONTENTION -> healResourceContention(item);
            case FM8_PROTOCOL_VIOLATION -> healProtocolViolation(item);
            case FM9_TRUST_DEGRADATION -> healTrustDegradation(item);

            // FC3: Emergent Failures — system-level recovery
            case FM10_CASCADING_FAILURE -> healCascadingFailure(item, plan);
            case FM11_OSCILLATION -> healOscillation(item);
            case FM12_PERFORMANCE_DEGRADATION -> healPerformanceDegradation(item);
            case FM13_PARTIAL_COMPLETION -> healPartialCompletion(plan);
            case FM14_RECOVERY_FAILURE -> healRecoveryFailure(item);
        };

        log.info("MAST healing: task={} mode={} strategy={} requiresHuman={}",
                item.getTaskKey(), mode, action.strategy(), action.requiresHuman());

        return action;
    }

    // ── FC1: Specification Healing ───────────────────────────────────────────

    private HealingAction healAmbiguousSpec(PlanItem item, Plan plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", item.getTaskKey());
        params.put("originalSpec", plan.getSpec());
        params.put("action", "create CONTEXT_MANAGER task to disambiguate");

        return new HealingAction(
                "CLARIFY_SPEC",
                "Specification is ambiguous — create a CONTEXT_MANAGER task to analyze "
                        + "the codebase and generate a disambiguation report",
                params, false);
    }

    private HealingAction healIncompleteReq(PlanItem item, Plan plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", item.getTaskKey());
        params.put("workerType", "CONTEXT_MANAGER");
        params.put("action", "enrich context and redispatch");

        return new HealingAction(
                "ENRICH_CONTEXT",
                "Requirements incomplete — dispatch CONTEXT_MANAGER to gather missing "
                        + "information, then redispatch the failed task with enriched context",
                params, false);
    }

    private HealingAction healConflictingConstraints(PlanItem item) {
        return new HealingAction(
                "ESCALATE_CONFLICT",
                "Conflicting constraints detected — requires human review to resolve "
                        + "which constraint takes precedence",
                Map.of("taskKey", item.getTaskKey()),
                true); // Requires human
    }

    private HealingAction healScopeCreep(PlanItem item, Plan plan) {
        return new HealingAction(
                "DECOMPOSE_TASK",
                "Scope creep detected — decompose the task into smaller sub-tasks "
                        + "using SUB_PLAN worker type",
                Map.of("taskKey", item.getTaskKey(), "workerType", "SUB_PLAN"),
                false);
    }

    // ── FC2: Inter-Agent Healing ─────────────────────────────────────────────

    private HealingAction healCommunicationBreakdown(PlanItem item, Plan plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", item.getTaskKey());
        params.put("action", "redispatch with enriched dependency results");
        params.put("enrichWorkerType", "CONTEXT_MANAGER");

        return new HealingAction(
                "REDISPATCH_WITH_CONTEXT",
                "Communication breakdown — create a new CONTEXT_MANAGER task as dependency, "
                        + "then redispatch the failed task with enriched context",
                params, false);
    }

    private HealingAction healCoordinationDeadlock(PlanItem item, Plan plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", item.getTaskKey());
        params.put("action", "break cycle by skipping weakest dependency edge");

        return new HealingAction(
                "BREAK_CYCLE",
                "Coordination deadlock — identify the weakest dependency edge in the cycle "
                        + "and skip it, allowing progress",
                params, false);
    }

    private HealingAction healResourceContention(PlanItem item) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", item.getTaskKey());
        params.put("action", "increase token budget by 50% and retry");
        params.put("budgetMultiplier", 1.5);

        return new HealingAction(
                "INCREASE_BUDGET",
                "Resource contention — increase token budget allocation "
                        + "and retry after cooldown period",
                params, false);
    }

    private HealingAction healProtocolViolation(PlanItem item) {
        return new HealingAction(
                "RETRY_WITH_STRICT_FORMAT",
                "Protocol violation — retry with explicit output format instructions "
                        + "in the prompt template",
                Map.of("taskKey", item.getTaskKey(),
                        "action", "add format enforcement to prompt"),
                false);
    }

    private HealingAction healTrustDegradation(PlanItem item) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", item.getTaskKey());
        params.put("action", "reassign to alternate worker profile");

        return new HealingAction(
                "REASSIGN_PROFILE",
                "Trust degradation — reassign task to an alternate worker profile "
                        + "with higher ELO score",
                params, false);
    }

    // ── FC3: Emergent Healing ────────────────────────────────────────────────

    private HealingAction healCascadingFailure(PlanItem item, Plan plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskKey", item.getTaskKey());
        params.put("action", "selective compensation on root cause task only");
        params.put("compensationType", "saga");

        return new HealingAction(
                "COMPENSATE_SELECTIVE",
                "Cascading failure — trace failure chain to root cause, "
                        + "compensate only the root task, then retry the chain",
                params, false);
    }

    private HealingAction healOscillation(PlanItem item) {
        return new HealingAction(
                "ABORT_REFINE",
                "Oscillation detected — abort self-refine loop, "
                        + "escalate to external REVIEW worker for human-grounded feedback",
                Map.of("taskKey", item.getTaskKey(),
                        "action", "force EXTERNAL_REVIEW via SelfRefineGateService"),
                false);
    }

    private HealingAction healPerformanceDegradation(PlanItem item) {
        return new HealingAction(
                "DRIFT_RESPONSE",
                "Performance degradation — trigger GP retraining and ELO recalibration, "
                        + "then retry with updated model",
                Map.of("taskKey", item.getTaskKey(),
                        "action", "trigger WorkerDriftMonitor refresh"),
                false);
    }

    private HealingAction healPartialCompletion(Plan plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("planId", plan.getId());
        params.put("action", "skip non-critical failed tasks and resume plan");

        return new HealingAction(
                "SKIP_AND_RESUME",
                "Partial completion — skip non-critical failed tasks, "
                        + "mark them as SKIPPED, and resume the plan to complete remaining work",
                params, false);
    }

    private HealingAction healRecoveryFailure(PlanItem item) {
        return new HealingAction(
                "MANUAL_INTERVENTION",
                "Recovery failure — all automated recovery strategies exhausted. "
                        + "Requires manual operator intervention to diagnose root cause",
                Map.of("taskKey", item.getTaskKey()),
                true); // Requires human
    }
}
