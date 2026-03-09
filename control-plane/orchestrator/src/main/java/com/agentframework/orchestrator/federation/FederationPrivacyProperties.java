package com.agentframework.orchestrator.federation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for differential privacy on federated metrics (#43).
 *
 * <p>Controls the noise calibration and budget for metrics shared between
 * federated orchestrator instances. Higher ε = less noise = less privacy;
 * lower ε = more noise = stronger privacy guarantees.</p>
 *
 * <p>Sensitivity values correspond to the maximum change a single task can
 * cause in each metric:
 * <ul>
 *   <li>ELO: K-factor = 32 (max rating change per match)</li>
 *   <li>Reward: range [-1, +1], so Δf = 2.0</li>
 * </ul>
 *
 * @param enabled            whether DP is active for federated metric exports
 * @param epsilon            per-query privacy parameter (default 1.0)
 * @param maxQueriesPerDay   daily budget as max number of DP queries (default 10)
 * @param eloSensitivity     max ELO change per single task (default 32.0)
 * @param rewardSensitivity  max reward range (default 2.0)
 */
@ConfigurationProperties(prefix = "federation.privacy")
public record FederationPrivacyProperties(
        boolean enabled,
        double epsilon,
        int maxQueriesPerDay,
        double eloSensitivity,
        double rewardSensitivity
) {
    public FederationPrivacyProperties {
        if (enabled && epsilon <= 0) {
            throw new IllegalArgumentException("federation.privacy.epsilon must be > 0 when enabled");
        }
    }
}
