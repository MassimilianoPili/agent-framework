package com.agentframework.orchestrator.gp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the context quality scoring service (#35).
 *
 * <p>Allows tuning the relative importance of the three quality metrics
 * (file relevance, entropy, KL divergence) that compose the final score.
 * Weights must sum to 1.0 for correct normalization.</p>
 *
 * @param weights the scoring metric weights (defaults: 0.45 / 0.30 / 0.25)
 */
@ConfigurationProperties(prefix = "gp.context-quality")
public record ContextQualityProperties(
    Weights weights
) {
    public ContextQualityProperties {
        if (weights == null) {
            weights = new Weights(0.45, 0.30, 0.25);
        }
    }

    /**
     * @param fileRelevance weight for file relevance score (default 0.45)
     * @param entropy       weight for entropy score (default 0.30)
     * @param klDivergence  weight for KL divergence score (default 0.25)
     */
    public record Weights(
        double fileRelevance,
        double entropy,
        double klDivergence
    ) {
        public Weights {
            if (fileRelevance == 0.0 && entropy == 0.0 && klDivergence == 0.0) {
                fileRelevance = 0.45;
                entropy = 0.30;
                klDivergence = 0.25;
            }
        }
    }
}
