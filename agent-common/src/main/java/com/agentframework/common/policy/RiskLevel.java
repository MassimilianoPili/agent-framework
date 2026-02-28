package com.agentframework.common.policy;

/**
 * Risk classification assigned by the HOOK_MANAGER to a task.
 *
 * <p>The orchestrator uses this to determine whether to hold the task in
 * {@code AWAITING_APPROVAL} state before dispatch:</p>
 * <ul>
 *   <li>{@code LOW}, {@code MEDIUM} — dispatch immediately</li>
 *   <li>{@code HIGH} — dispatch immediately but with extra audit logging</li>
 *   <li>{@code CRITICAL} — hold in {@code AWAITING_APPROVAL} until a human approves</li>
 * </ul>
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
