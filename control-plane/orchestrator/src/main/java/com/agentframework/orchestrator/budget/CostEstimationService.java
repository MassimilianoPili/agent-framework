package com.agentframework.orchestrator.budget;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Estimates the cost of a task based on token usage and model pricing (#26L1).
 *
 * <p>Formula: {@code (inputTokens × inputPrice + outputTokens × outputPrice) / 1_000_000}.
 * Uses model-specific pricing when available, otherwise falls back to {@code defaultPricing}.
 * Returns {@code null} if token counts are unavailable or pricing is not configured.
 */
@Service
public class CostEstimationService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final CostProperties properties;

    public CostEstimationService(CostProperties properties) {
        this.properties = properties;
    }

    /**
     * Estimates the USD cost for the given token usage.
     *
     * @param inputTokens  input tokens consumed (null-safe)
     * @param outputTokens output tokens consumed (null-safe)
     * @param model        AI model identifier used for pricing lookup (null-safe)
     * @return estimated cost in USD (scale 6), or null if tokens are unavailable
     */
    public BigDecimal estimate(Long inputTokens, Long outputTokens, String model) {
        if (inputTokens == null && outputTokens == null) {
            return null;
        }

        CostProperties.ModelPricing pricing = resolvePricing(model);
        if (pricing == null) {
            return null;
        }

        long in = inputTokens != null ? inputTokens : 0;
        long out = outputTokens != null ? outputTokens : 0;

        BigDecimal inputCost = BigDecimal.valueOf(in)
                .multiply(pricing.inputPerMillion())
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(out)
                .multiply(pricing.outputPerMillion())
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }

    private CostProperties.ModelPricing resolvePricing(String model) {
        if (model != null && properties.models() != null) {
            CostProperties.ModelPricing exact = properties.models().get(model);
            if (exact != null) return exact;
        }
        return properties.defaultPricing();
    }
}
