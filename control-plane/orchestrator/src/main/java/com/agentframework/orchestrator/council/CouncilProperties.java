package com.agentframework.orchestrator.council;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the advisory council feature.
 *
 * <p>Bound from the {@code council.*} namespace in {@code application.yml}.</p>
 *
 * <pre>
 * council:
 *   enabled: true
 *   max-members: 5
 *   pre-planning-enabled: true
 *   task-session-enabled: true
 *   quadratic-voting-enabled: false
 *   base-voice-credits: 100
 * </pre>
 */
@ConfigurationProperties(prefix = "council")
public record CouncilProperties(

    /**
     * Master switch. When false, all council logic is bypassed and
     * {@code Plan.councilReport} remains null. Existing plans continue to work.
     */
    boolean enabled,

    /**
     * Maximum number of MANAGER/SPECIALIST members to consult per session.
     * The selector may identify more candidates; only the top {@code maxMembers}
     * (by relevance score) will be consulted.
     */
    int maxMembers,

    /**
     * Whether to run the pre-planning session in {@code OrchestrationService.createAndStart()}.
     * When false, Plan.councilReport is null but in-plan COUNCIL_MANAGER tasks still work.
     */
    boolean prePlanningEnabled,

    /**
     * Whether to handle COUNCIL_MANAGER tasks in-plan via {@code CouncilService.conductTaskSession()}.
     * When false, COUNCIL_MANAGER tasks are skipped (marked DONE immediately with empty result).
     */
    boolean taskSessionEnabled,

    /**
     * When true, uses {@code SubmodularSelector} (CELF greedy algorithm) instead of the LLM
     * for council member selection. The submodular approach maximises topic coverage diversity
     * with a provable (1 - 1/e) ≈ 63% optimality guarantee.
     */
    boolean submodularSelectionEnabled,

    /**
     * When true, enables Quadratic Voting (#49) for council recommendation weighting.
     * Each member receives voice credits and allocates votes to recommendations with
     * quadratic cost (k votes cost k² credits). This expresses preference intensity.
     */
    boolean quadraticVotingEnabled,

    /**
     * Base voice credit budget per council member for Quadratic Voting.
     * Formula: {@code max(70, min(160, base + floor((elo - 1600) / 100) * 10))}.
     * Currently all council members use the base value (no ELO differentiation).
     */
    int baseVoiceCredits

) {
    public CouncilProperties {
        if (maxMembers <= 0) {
            throw new IllegalArgumentException("council.max-members must be > 0, got: " + maxMembers);
        }
        if (baseVoiceCredits < 10) {
            throw new IllegalArgumentException("council.base-voice-credits must be >= 10, got: " + baseVoiceCredits);
        }
    }
}
