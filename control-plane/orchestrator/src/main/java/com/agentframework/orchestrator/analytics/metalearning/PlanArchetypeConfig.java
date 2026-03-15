package com.agentframework.orchestrator.analytics.metalearning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Cross-Plan Meta-Learning.
 *
 * @param maxArchetypes       maximum number of archetypes to store in registry
 * @param similarityThreshold minimum cosine similarity for retrieval match
 * @param minSuccessRate      minimum success rate for an archetype to be recommended (non-contrastive)
 * @param deduplicationThreshold similarity above which archetypes are merged (not duplicated)
 */
@ConfigurationProperties(prefix = "agent-framework.plan-archetype")
public record PlanArchetypeConfig(
        int maxArchetypes,
        double similarityThreshold,
        double minSuccessRate,
        double deduplicationThreshold
) {
    public PlanArchetypeConfig {
        if (maxArchetypes <= 0) maxArchetypes = 200;
        if (similarityThreshold <= 0 || similarityThreshold >= 1) similarityThreshold = 0.7;
        if (minSuccessRate < 0 || minSuccessRate > 1) minSuccessRate = 0.5;
        if (deduplicationThreshold <= 0 || deduplicationThreshold >= 1) deduplicationThreshold = 0.95;
    }

    public static PlanArchetypeConfig defaults() {
        return new PlanArchetypeConfig(200, 0.7, 0.5, 0.95);
    }
}
