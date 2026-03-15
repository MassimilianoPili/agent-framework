package com.agentframework.orchestrator.analytics.prm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Process Reward Model.
 *
 * @param objectiveWeight weight for objective metrics (compile/test pass) in combined score
 * @param gpWeight        weight for GP posterior mu in combined score (auto-computed as 1 - objectiveWeight)
 * @param decayFactor     temporal decay for older step rewards in trajectory scoring
 */
@ConfigurationProperties(prefix = "agent-framework.prm")
public record PrmConfig(
        double objectiveWeight,
        double gpWeight,
        double decayFactor
) {
    public PrmConfig {
        if (objectiveWeight <= 0 || objectiveWeight >= 1) objectiveWeight = 0.6;
        if (gpWeight <= 0) gpWeight = 1.0 - objectiveWeight;
        if (decayFactor <= 0 || decayFactor > 1) decayFactor = 0.95;
    }

    public static PrmConfig defaults() {
        return new PrmConfig(0.6, 0.4, 0.95);
    }
}
