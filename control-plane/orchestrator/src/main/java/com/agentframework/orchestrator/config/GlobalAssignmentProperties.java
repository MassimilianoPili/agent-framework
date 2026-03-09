package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for global task assignment via Hungarian Algorithm (#42).
 *
 * <p>When enabled, dispatchable items are assigned to worker profiles using
 * the Kuhn-Munkres O(n^3) algorithm for globally optimal matching, instead of
 * the default greedy per-task GP selection.</p>
 *
 * <pre>
 * global-assignment:
 *   enabled: false
 *   critical-path-boost: 0.2
 *   min-dispatchable: 2
 * </pre>
 */
@ConfigurationProperties(prefix = "global-assignment")
public record GlobalAssignmentProperties(

    /** Master switch. When false, dispatch falls back to per-task greedy GP selection. */
    boolean enabled,

    /**
     * Multiplicative boost applied to GP mu for tasks on the critical path.
     * Effective mu = mu * (1 + criticalPathBoost) before negation into cost.
     * Higher values give critical-path tasks priority for better profiles.
     */
    double criticalPathBoost,

    /**
     * Minimum number of dispatchable items required to trigger global assignment.
     * Below this threshold, per-task greedy selection is used (overhead not justified).
     */
    int minDispatchable

) {}
