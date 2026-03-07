package com.agentframework.orchestrator.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Configurable model pricing for per-task cost estimation (#26L1).
 *
 * <p>Pricing is per million tokens. Example configuration:
 * <pre>
 * cost:
 *   default-pricing:
 *     input-per-million: 3.0
 *     output-per-million: 15.0
 *   models:
 *     claude-sonnet-4-20250514:
 *       input-per-million: 3.0
 *       output-per-million: 15.0
 * </pre>
 */
@ConfigurationProperties(prefix = "cost")
public record CostProperties(
    Map<String, ModelPricing> models,
    ModelPricing defaultPricing
) {
    public record ModelPricing(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {}
}
