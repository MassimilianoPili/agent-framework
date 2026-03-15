package com.agentframework.orchestrator.analytics.shapley;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Causal Shapley value computation.
 *
 * @param maxCoalitions     maximum Monte Carlo coalition samples (controls accuracy vs speed)
 * @param minDataPoints     minimum observational data points per variable for reliable estimation
 * @param interventionType  type of causal intervention: "remove" (do(X=0)) or "counterfactual"
 */
@ConfigurationProperties(prefix = "agent-framework.causal-shapley")
public record CausalShapleyConfig(
        int maxCoalitions,
        int minDataPoints,
        String interventionType
) {
    public CausalShapleyConfig {
        if (maxCoalitions <= 0) maxCoalitions = 1000;
        if (minDataPoints <= 0) minDataPoints = 10;
        if (interventionType == null || interventionType.isBlank()) interventionType = "remove";
    }

    public static CausalShapleyConfig defaults() {
        return new CausalShapleyConfig(1000, 10, "remove");
    }
}
