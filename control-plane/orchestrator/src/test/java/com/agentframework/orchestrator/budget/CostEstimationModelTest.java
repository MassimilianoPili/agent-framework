package com.agentframework.orchestrator.budget;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that CostEstimationService applies per-model pricing correctly (#20 completion).
 *
 * <ul>
 *   <li>Known model → uses its specific price table.</li>
 *   <li>Unknown model → falls back to default pricing.</li>
 * </ul>
 */
class CostEstimationModelTest {

    private static final BigDecimal HAIKU_INPUT  = new BigDecimal("0.80");   // $0.80 / 1M
    private static final BigDecimal HAIKU_OUTPUT = new BigDecimal("4.00");   // $4.00 / 1M
    private static final BigDecimal DEFAULT_INPUT  = new BigDecimal("3.00");
    private static final BigDecimal DEFAULT_OUTPUT = new BigDecimal("15.00");

    private CostEstimationService serviceWith(Map<String, CostProperties.ModelPricing> models) {
        var defaultPricing = new CostProperties.ModelPricing(DEFAULT_INPUT, DEFAULT_OUTPUT);
        var props = new CostProperties(models, defaultPricing);
        return new CostEstimationService(props);
    }

    @Test
    void estimate_withHaikuModel_appliesHaikuPricing() {
        var haikuPricing = new CostProperties.ModelPricing(HAIKU_INPUT, HAIKU_OUTPUT);
        var service = serviceWith(Map.of("claude-haiku-4-5-20251001", haikuPricing));

        // 1000 input tokens + 500 output tokens with Haiku pricing
        // expected = (1000 * 0.80 + 500 * 4.00) / 1_000_000 = (800 + 2000) / 1_000_000 = 0.002800
        BigDecimal result = service.estimate(1000L, 500L, "claude-haiku-4-5-20251001");

        assertThat(result).isNotNull();
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.002800"));
    }

    @Test
    void estimate_withUnknownModel_fallsBackToDefaultPricing() {
        var service = serviceWith(Map.of());  // no model-specific entries

        // 1000 input tokens + 500 output tokens with default pricing (3.00 / 15.00)
        // expected = (1000 * 3.00 + 500 * 15.00) / 1_000_000 = (3000 + 7500) / 1_000_000 = 0.010500
        BigDecimal result = service.estimate(1000L, 500L, "claude-unknown-model");

        assertThat(result).isNotNull();
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.010500"));
    }
}
