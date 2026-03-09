package com.agentframework.orchestrator.budget;

import com.agentframework.common.policy.HookPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PID controller for adaptive token budget adjustment (#37).
 *
 * <p>Adjusts {@link HookPolicy#maxTokenBudget()} per (planId, workerType) based on
 * the cumulative error between estimated and actual token consumption. The feedback
 * loop runs at zero LLM cost — state is purely in-memory.</p>
 *
 * <h3>Control loop</h3>
 * <pre>
 *   DISPATCH:  resolvePolicy() → adjustPolicy() → AgentTask(policy)
 *   COMPLETE:  recordUsage()   → update()       → error state updated
 *   CLEANUP:   checkPlanCompletion() → evictPlan() → memory freed
 * </pre>
 *
 * <h3>PID formula</h3>
 * <pre>
 *   u(t) = Kp × e(t) + Ki × ∫e(τ)dτ + Kd × de/dt
 *   error = actualTokens − setpoint  (positive = overuse → budget increases)
 *   adjustedBudget = clamp(baseBudget + u, minBudget, maxBudget)
 * </pre>
 *
 * <p>The setpoint is either the {@code estimatedTokens} from the HookPolicy (when available)
 * or an Exponential Moving Average of actual consumption (adaptive fallback).</p>
 *
 * @see PidBudgetProperties
 */
@Service
@ConditionalOnProperty(prefix = "budget.pid", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PidBudgetProperties.class)
public class PidBudgetController {

    private static final Logger log = LoggerFactory.getLogger(PidBudgetController.class);
    private static final double EMA_ALPHA = 0.3;

    private final PidBudgetProperties props;
    private final ConcurrentHashMap<StateKey, PidState> states = new ConcurrentHashMap<>();

    record StateKey(UUID planId, String workerType) {}

    static class PidState {
        double integral = 0.0;
        double previousError = Double.NaN;
        double lastDerivative = 0.0;
        long sampleCount = 0;
        double runningAvgTokens = 0.0;
    }

    public PidBudgetController(PidBudgetProperties props) {
        this.props = props;
    }

    /**
     * Adjusts the maxTokenBudget in the given policy based on accumulated PID state.
     *
     * @param planId     the plan ID
     * @param workerType the worker type name
     * @param original   the original policy (may be null)
     * @return adjusted policy with modified maxTokenBudget, or the original if below warmup
     */
    public HookPolicy adjustPolicy(UUID planId, String workerType, HookPolicy original) {
        if (original == null) {
            return null;
        }

        StateKey key = new StateKey(planId, workerType);
        PidState state = states.get(key);

        if (state == null || state.sampleCount < props.warmupSamples()) {
            return original;
        }

        double u = props.kp() * state.previousError
                 + props.ki() * state.integral
                 + props.kd() * state.lastDerivative;

        int baseBudget = original.maxTokenBudget() != null
                ? original.maxTokenBudget()
                : (int) state.runningAvgTokens;

        int adjustedBudget = clamp(baseBudget + (int) u, props.minBudget(), props.maxBudget());

        log.debug("PID adjust: plan={} worker={} base={} u={} adjusted={}",
                  planId, workerType, baseBudget, (int) u, adjustedBudget);

        return withMaxTokenBudget(original, adjustedBudget);
    }

    /**
     * Updates PID state after a task completes.
     *
     * @param planId          the plan ID
     * @param workerType      the worker type name
     * @param estimatedTokens estimated tokens from HookPolicy (nullable)
     * @param actualTokens    actual tokens consumed
     */
    public void update(UUID planId, String workerType, Integer estimatedTokens, long actualTokens) {
        StateKey key = new StateKey(planId, workerType);
        PidState state = states.computeIfAbsent(key, k -> new PidState());

        synchronized (state) {
            state.sampleCount++;

            // EMA for adaptive setpoint
            if (state.sampleCount == 1) {
                state.runningAvgTokens = actualTokens;
            } else {
                state.runningAvgTokens = EMA_ALPHA * actualTokens + (1 - EMA_ALPHA) * state.runningAvgTokens;
            }

            double setpoint = estimatedTokens != null ? estimatedTokens : state.runningAvgTokens;
            double error = actualTokens - setpoint;

            // Integral with anti-windup
            state.integral = clamp(state.integral + error,
                    -props.integralLimit(), props.integralLimit());

            // Derivative (first sample: derivative = 0)
            state.lastDerivative = Double.isNaN(state.previousError) ? 0.0 : error - state.previousError;
            state.previousError = error;
        }
    }

    /**
     * Removes all PID state for a completed plan, freeing memory.
     *
     * @param planId the plan ID to evict
     */
    public void evictPlan(UUID planId) {
        states.keySet().removeIf(key -> key.planId().equals(planId));
    }

    // ── Test visibility ──────────────────────────────────────────────────────

    /** Package-private: exposes state for test assertions. */
    PidState getState(UUID planId, String workerType) {
        return states.get(new StateKey(planId, workerType));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static HookPolicy withMaxTokenBudget(HookPolicy p, int adjusted) {
        return new HookPolicy(
                p.allowedTools(), p.ownedPaths(), p.allowedMcpServers(), p.auditEnabled(),
                adjusted,
                p.allowedNetworkHosts(), p.requiredHumanApproval(), p.approvalTimeoutMinutes(),
                p.riskLevel(), p.estimatedTokens(), p.shouldSnapshot()
        );
    }
}
