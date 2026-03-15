package com.agentframework.orchestrator.analytics.pareto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Token Cost Pareto Optimizer.
 *
 * @param qualityThreshold GP mu threshold above which cost-efficient mode activates
 * @param sigmaThreshold   GP sigma below which cost-efficient mode is safe
 * @param costWeight       weight for cost objective in multi-objective scoring (quality weight = 1 - costWeight)
 * @param cascadeEnabled   whether to use cascade (cheap first, escalate if uncertain) vs Pareto frontier
 */
@ConfigurationProperties(prefix = "agent-framework.pareto")
public record ParetoConfig(
        double qualityThreshold,
        double sigmaThreshold,
        double costWeight,
        boolean cascadeEnabled
) {
    public ParetoConfig {
        if (qualityThreshold <= 0 || qualityThreshold >= 1) qualityThreshold = 0.7;
        if (sigmaThreshold <= 0) sigmaThreshold = 0.3;
        if (costWeight < 0 || costWeight > 1) costWeight = 0.3;
    }

    public static ParetoConfig defaults() {
        return new ParetoConfig(0.7, 0.3, 0.3, true);
    }
}
