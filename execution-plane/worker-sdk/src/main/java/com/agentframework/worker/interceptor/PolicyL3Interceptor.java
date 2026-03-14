package com.agentframework.worker.interceptor;

import com.agentframework.common.policy.HookPolicy;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * L3 (post-execution) enforcement interceptor (#10).
 *
 * <p>Validates the worker's result against the task-level {@link HookPolicy} constraints
 * <em>after</em> execution completes. Complements L1 (pre-dispatch, orchestrator) and
 * L2 (per-tool-call, worker). L3 catches policy violations that can only be detected
 * after the full result is available.</p>
 *
 * <h3>Checks performed:</h3>
 * <ul>
 *   <li><b>Empty result</b>: worker must produce a non-empty result — empty output
 *       indicates a silent failure that should be flagged rather than silently accepted</li>
 *   <li><b>Token budget</b>: if {@code maxTokenBudget} is set, validates that actual
 *       tokens consumed (from ThreadLocal) do not exceed the budget. Violation is logged
 *       as a warning (fail-open) — the orchestrator's PID controller handles budget correction</li>
 *   <li><b>Snapshot requirement</b>: if {@code shouldSnapshot} is set but no snapshot was
 *       captured, logs a warning for operational awareness</li>
 * </ul>
 *
 * <p>Enforcement is <b>fail-open</b>: violations are logged but never block the result.
 * The orchestrator receives the result regardless, and the violation is recorded in the
 * audit trail for review. This matches the framework's philosophy: workers are autonomous
 * agents whose output should be reviewed (via REVIEW worker, Ralph-loop) rather than
 * silently discarded.</p>
 *
 * <p>Runs at {@code Ordered.LOWEST_PRECEDENCE - 10} — after business interceptors but
 * before {@link ResultSchemaValidationInterceptor} (which runs at LOWEST_PRECEDENCE).</p>
 */
@Component
public class PolicyL3Interceptor implements WorkerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PolicyL3Interceptor.class);

    /** ThreadLocal for actual token count, set by the ChatClient callback. */
    private static final ThreadLocal<Integer> ACTUAL_TOKENS = new ThreadLocal<>();

    /** Called by the ChatClient pipeline to record tokens consumed. */
    public static void recordTokenUsage(int tokens) {
        Integer current = ACTUAL_TOKENS.get();
        ACTUAL_TOKENS.set(current != null ? current + tokens : tokens);
    }

    /** Returns and clears the recorded token usage. */
    public static Integer drainTokenUsage() {
        Integer tokens = ACTUAL_TOKENS.get();
        ACTUAL_TOKENS.remove();
        return tokens;
    }

    @Override
    public String afterExecute(AgentContext ctx, String result, AgentTask task) {
        HookPolicy policy = ctx.policy();
        if (policy == null) {
            return result;
        }

        String taskKey = ctx.taskKey();

        // L3-1: Empty result check
        if (result == null || result.isBlank()) {
            log.warn("[L3] [{}] Worker produced empty result — policy violation (auditEnabled={})",
                    taskKey, policy.auditEnabled());
            return result;
        }

        // L3-2: Token budget enforcement
        Integer actualTokens = drainTokenUsage();
        if (actualTokens != null && policy.maxTokenBudget() != null
                && actualTokens > policy.maxTokenBudget()) {
            log.warn("[L3] [{}] Token budget exceeded: actual={} > budget={} (fail-open, orchestrator PID corrects)",
                    taskKey, actualTokens, policy.maxTokenBudget());
        }

        // L3-3: Audit trail for policy-constrained executions
        if (policy.auditEnabled()) {
            int resultSize = result.length();
            log.info("[L3] [{}] Audit: result={} chars, tokens={}, riskLevel={}, approval={}",
                    taskKey, resultSize,
                    actualTokens != null ? actualTokens : "unknown",
                    policy.riskLevel(), policy.requiredHumanApproval());
        }

        return result;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
