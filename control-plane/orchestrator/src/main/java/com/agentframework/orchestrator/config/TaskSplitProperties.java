package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for automatic task splitting (#26L2).
 *
 * <p>When enabled, tasks estimated to exceed a token threshold are automatically
 * split into smaller sub-tasks via the SUB_PLAN mechanism before dispatch.
 * The cost estimate uses a heuristic (description length × multiplier +
 * dependency count bonus) boosted by GP uncertainty when available.</p>
 *
 * <pre>
 * task-split:
 *   enabled: false
 *   threshold-tokens: 50000
 *   threshold-action: SPLIT
 *   max-split-attempts: 1
 *   description-length-multiplier: 25
 *   gp-sigma2-threshold: 0.5
 * </pre>
 */
@ConfigurationProperties(prefix = "task-split")
public record TaskSplitProperties(

    /** Master switch. When false, no split evaluation or conversion occurs. */
    boolean enabled,

    /**
     * Estimated input token threshold. Tasks with estimated input tokens
     * above this value trigger the configured action.
     */
    long thresholdTokens,

    /**
     * Action to take when a task exceeds the threshold.
     * <ul>
     *   <li>{@code WARN} — log warning, proceed with dispatch</li>
     *   <li>{@code SPLIT} — convert task to SUB_PLAN and decompose into sub-tasks</li>
     *   <li>{@code BLOCK} — transition to AWAITING_APPROVAL for human review</li>
     * </ul>
     */
    ThresholdAction thresholdAction,

    /** Maximum number of split attempts per item. Guards against infinite recursion. */
    int maxSplitAttempts,

    /**
     * Heuristic multiplier: description characters → estimated input tokens.
     * Empirical: 1 char of description ≈ 25 input tokens including context injection.
     */
    int descriptionLengthMultiplier,

    /**
     * GP uncertainty threshold. When {@code sigma²} exceeds this value,
     * the token estimate is boosted by 1.5× (upper-bound for uncertain tasks).
     */
    double gpSigma2Threshold

) {
    public enum ThresholdAction { WARN, SPLIT, BLOCK }
}
