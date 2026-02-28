package com.agentframework.common.policy;

/**
 * Human approval requirement for a task before dispatch.
 *
 * <p>Set by the HOOK_MANAGER worker on a per-task basis via {@link HookPolicy#requiredHumanApproval()}.
 * The orchestrator enforces this by transitioning items to {@code AWAITING_APPROVAL} when
 * the mode is not {@code NONE}.</p>
 */
public enum ApprovalMode {
    /** No approval required — dispatch immediately (default). */
    NONE,
    /** Hold dispatch indefinitely until a human approves via POST .../approve. */
    BLOCK,
    /** Hold up to {@link HookPolicy#approvalTimeoutMinutes()} minutes, then transition to FAILED. */
    NOTIFY_TIMEOUT
}
