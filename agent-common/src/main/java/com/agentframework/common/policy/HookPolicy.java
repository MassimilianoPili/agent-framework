package com.agentframework.common.policy;

import java.util.List;

/**
 * Contextual execution policy for a single agent task.
 *
 * <p>Produced by the {@code HOOK_MANAGER} worker, which analyzes the full plan
 * and generates fine-grained constraints for each downstream task. Passed as a
 * field in {@code AgentTask} to workers via the message broker.</p>
 *
 * <p>This record is the single source of truth shared between the orchestrator
 * (control-plane) and the worker-sdk (execution-plane). It replaces the previous
 * duplicate copies in {@code com.agentframework.orchestrator.hooks} and
 * {@code com.agentframework.worker.policy}.</p>
 *
 * <p>Enforcement layers:</p>
 * <ul>
 *   <li><b>CLI workers</b>: serialized to {@code .claude/settings.json} in the
 *       workspace before spawn</li>
 *   <li><b>Java workers</b>: read from {@code AgentContext} by
 *       {@code PolicyEnforcingToolCallback}, overriding static {@code PolicyProperties}</li>
 * </ul>
 *
 * <p>A {@code null} policy means "no task-level override" — enforcement falls back
 * to the worker's static configuration ({@code application.yml}).</p>
 */
public record HookPolicy(

    // ── Original 4 fields ─────────────────────────────────────────────────

    /** Tool names this task is allowed to invoke. Empty = inherit static config. */
    List<String> allowedTools,

    /** File path prefixes this task may write to. Empty = inherit static config. */
    List<String> ownedPaths,

    /** MCP server names this task is allowed to call. Empty = inherit static config. */
    List<String> allowedMcpServers,

    /** Whether audit logging is required for this task. */
    boolean auditEnabled,

    // ── Extended 7 fields ─────────────────────────────────────────────────

    /**
     * Per-task token ceiling (nullable — no task-level limit if null).
     * Takes precedence over the plan-level budget for this specific task.
     */
    Integer maxTokenBudget,

    /**
     * Outbound network hosts this task may connect to (e.g., {@code "api.github.com"}).
     * Empty = no network restriction (for workers that don't enforce network policy).
     */
    List<String> allowedNetworkHosts,

    /**
     * Human approval requirement before this task is dispatched.
     * {@code null} / {@code NONE} = dispatch immediately.
     * {@code BLOCK} = hold in {@code AWAITING_APPROVAL} indefinitely.
     * {@code NOTIFY_TIMEOUT} = hold up to {@link #approvalTimeoutMinutes()}, then fail.
     */
    ApprovalMode requiredHumanApproval,

    /**
     * Minutes to wait for approval when {@link #requiredHumanApproval()} is
     * {@link ApprovalMode#NOTIFY_TIMEOUT}. Zero = immediate timeout (effectively auto-fail).
     */
    int approvalTimeoutMinutes,

    /**
     * Risk classification assigned by the HOOK_MANAGER.
     * {@link RiskLevel#CRITICAL} causes the dispatcher to transition the item to
     * {@code AWAITING_APPROVAL} instead of dispatching immediately.
     */
    RiskLevel riskLevel,

    /**
     * Estimated token consumption for this task (nullable).
     * Used by the token budget service for pre-dispatch budget checks.
     */
    Integer estimatedTokens,

    /**
     * Whether a workspace snapshot should be captured before executing this task.
     * Useful for compensation rollback ({@code COMPENSATOR_MANAGER}) and audit trails.
     */
    boolean shouldSnapshot

) {
    public HookPolicy {
        allowedTools        = allowedTools != null        ? List.copyOf(allowedTools)        : List.of();
        ownedPaths          = ownedPaths != null          ? List.copyOf(ownedPaths)          : List.of();
        allowedMcpServers   = allowedMcpServers != null   ? List.copyOf(allowedMcpServers)   : List.of();
        allowedNetworkHosts = allowedNetworkHosts != null ? List.copyOf(allowedNetworkHosts) : List.of();
    }
}
