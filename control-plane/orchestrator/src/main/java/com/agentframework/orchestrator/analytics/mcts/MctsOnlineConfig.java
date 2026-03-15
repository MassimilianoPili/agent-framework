package com.agentframework.orchestrator.analytics.mcts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for MCTS Online Policy Learning.
 *
 * @param emaAlpha       EMA decay factor for rewards (0 &lt; α ≤ 1). Higher = faster adaptation to new rewards
 * @param raveK          RAVE equivalence parameter. Controls RAVE→MC transition speed
 * @param bamcpSamples   number of GP posterior samples for BAMCP root initialization
 * @param explorationC   UCB exploration constant (c in PUCT formula)
 * @param decayIntervalMs interval between EMA decay sweeps (milliseconds)
 */
@ConfigurationProperties(prefix = "agent-framework.mcts-online")
public record MctsOnlineConfig(
        double emaAlpha,
        int raveK,
        int bamcpSamples,
        double explorationC,
        long decayIntervalMs
) {
    public MctsOnlineConfig {
        if (emaAlpha <= 0 || emaAlpha > 1) emaAlpha = 0.3;
        if (raveK <= 0) raveK = 500;
        if (bamcpSamples <= 0) bamcpSamples = 10;
        if (explorationC <= 0) explorationC = 1.41;
        if (decayIntervalMs <= 0) decayIntervalMs = 60_000;
    }

    public static MctsOnlineConfig defaults() {
        return new MctsOnlineConfig(0.3, 500, 10, 1.41, 60_000);
    }
}
