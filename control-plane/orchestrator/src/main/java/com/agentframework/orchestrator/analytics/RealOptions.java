package com.agentframework.orchestrator.analytics;

import java.util.List;

/**
 * Real Options Theory for task deferral valuation.
 *
 * <p>Applies perpetual American option pricing to dispatch decisions.
 * Dispatching a task consumes tokens (irreversible investment). Waiting allows
 * uncertainty to resolve as other tasks complete and reveal information.</p>
 *
 * <p>Key formula (Dixit &amp; Pindyck 1994):</p>
 * <pre>
 *   β = 0.5 - (r-δ)/σ² + sqrt[((r-δ)/σ² - 0.5)² + 2r/σ²]
 *   V* = β/(β-1) × I
 *
 *   V ≥ V*  →  execute now (NPV dominates option value)
 *   V &lt; V*  →  defer (waiting has positive option value)
 * </pre>
 *
 * @see <a href="https://press.princeton.edu/books/hardcover/9780691034102/investment-under-uncertainty">
 *     Dixit &amp; Pindyck (1994), Investment under Uncertainty</a>
 */
public final class RealOptions {

    private RealOptions() {}

    static final double EPSILON = 1e-12;

    /**
     * Computes β (perpetual option exponent).
     *
     * <p>β = 0.5 - (r-δ)/σ² + sqrt[((r-δ)/σ² - 0.5)² + 2r/σ²]</p>
     *
     * <p>Requires σ &gt; 0 and r &gt; 0. When σ → 0, β → ∞ (NPV rule).
     * When β ≤ 1, the threshold becomes infinite or negative — means never defer.</p>
     *
     * @param discountRate     r: cost of opportunity delay (&gt; 0)
     * @param convenienceYield δ: value of having the task done now (≥ 0)
     * @param volatility       σ: uncertainty of task value (&gt; 0)
     * @return β exponent, or {@link Double#MAX_VALUE} if σ ≈ 0
     */
    static double computeBeta(double discountRate, double convenienceYield, double volatility) {
        if (volatility < EPSILON) {
            return Double.MAX_VALUE; // σ → 0: NPV rule (threshold = cost)
        }
        if (discountRate < EPSILON) {
            return 1.0; // r = 0: no time value, β = 1 (never defer)
        }

        double sigma2 = volatility * volatility;
        double drift = (discountRate - convenienceYield) / sigma2;

        double term1 = 0.5 - drift;
        double term2 = (drift - 0.5) * (drift - 0.5) + 2.0 * discountRate / sigma2;

        return term1 + Math.sqrt(term2);
    }

    /**
     * Optimal execution threshold: V* = β/(β-1) × I.
     *
     * <p>Above V*, the NPV dominates the option value of waiting.
     * With β → ∞, V* → I (classical NPV rule).</p>
     *
     * @param investmentCost I: irreversible cost (token budget)
     * @param beta           β: option exponent (&gt; 1 for finite threshold)
     * @return V* threshold, or investmentCost if β ≤ 1
     */
    static double executionThreshold(double investmentCost, double beta) {
        if (beta <= 1.0 + EPSILON) {
            return investmentCost; // β ≤ 1: threshold = cost (always execute if NPV > 0)
        }
        return (beta / (beta - 1.0)) * investmentCost;
    }

    /**
     * Net Present Value: NPV = V - I.
     *
     * <p>Classical decision rule: execute if NPV &gt; 0.
     * Real Options may recommend deferral even with positive NPV.</p>
     */
    static double npv(double expectedReward, double investmentCost) {
        return expectedReward - investmentCost;
    }

    /**
     * Option value F(V): value of the deferral option.
     *
     * <p>If V ≥ V*: 0 (exercise immediately, no residual waiting value).
     * If V &lt; V*: A × V^β where A = (V*-I) / V*^β.</p>
     *
     * <p>Uses log-space for A computation to avoid overflow with large β.</p>
     *
     * @param expectedReward V: expected value of the task
     * @param threshold      V*: optimal execution threshold
     * @param beta           β: option exponent
     * @param investmentCost I: irreversible cost
     * @return option value F(V) ≥ 0
     */
    static double optionValue(double expectedReward, double threshold, double beta,
                               double investmentCost) {
        if (expectedReward >= threshold) {
            return 0.0; // above threshold: exercise immediately
        }
        if (expectedReward < EPSILON || threshold < EPSILON) {
            return 0.0;
        }

        // A = (V* - I) / V*^β  computed in log-space
        double numerator = threshold - investmentCost;
        if (numerator <= 0) {
            return 0.0; // degenerate: threshold ≤ cost
        }

        // F(V) = A × V^β = (V*-I) × (V/V*)^β
        // = (V*-I) × exp(β × ln(V/V*))
        double logRatio = Math.log(expectedReward / threshold);
        double fv = numerator * Math.exp(beta * logRatio);

        return Math.max(0.0, fv);
    }

    /**
     * Complete deferral decision combining NPV, option value, and threshold.
     *
     * <p>shouldDefer = true when V &lt; V* (option value &gt; 0).
     * shouldDefer = false when V ≥ V* (executing now is optimal).</p>
     *
     * @param expectedReward  V: GP μ (expected reward)
     * @param volatility      σ: GP σ (uncertainty)
     * @param investmentCost  I: token cost
     * @param discountRate    r: cost of delay
     * @param convenienceYield δ: urgency
     * @return deferral decision with full analysis
     */
    static DeferralDecision shouldDefer(double expectedReward, double volatility,
                                         double investmentCost, double discountRate,
                                         double convenienceYield) {
        double beta = computeBeta(discountRate, convenienceYield, volatility);
        double threshold = executionThreshold(investmentCost, beta);
        double currentNpv = npv(expectedReward, investmentCost);
        double ov = optionValue(expectedReward, threshold, beta, investmentCost);

        boolean defer = expectedReward < threshold;

        String reason;
        if (defer) {
            if (currentNpv <= 0) {
                reason = String.format("Defer: NPV=%.3f < 0 and V=%.3f < V*=%.3f",
                        currentNpv, expectedReward, threshold);
            } else {
                reason = String.format("Defer: NPV=%.3f > 0 but V=%.3f < V*=%.3f (option premium)",
                        currentNpv, expectedReward, threshold);
            }
        } else {
            reason = String.format("Execute: V=%.3f >= V*=%.3f (NPV=%.3f)",
                    expectedReward, threshold, currentNpv);
        }

        return new DeferralDecision(defer, ov, threshold, currentNpv, beta, reason);
    }

    /**
     * Adjusts convenience yield for task urgency.
     *
     * <p>δ_effective = δ_base + urgencyWeight × urgencyFactor.
     * High urgency → high δ → lower β → lower V* → less deferral.</p>
     *
     * @param baseYield     δ_base: default convenience yield
     * @param urgencyWeight weight for urgency factor
     * @param urgencyFactor ∈ [0,1]: 0=no urgency, 1=maximum urgency
     * @return effective convenience yield
     */
    static double adjustedConvenienceYield(double baseYield, double urgencyWeight,
                                            double urgencyFactor) {
        return baseYield + urgencyWeight * Math.max(0, Math.min(1, urgencyFactor));
    }

    /**
     * Estimates volatility from a sample of rewards.
     *
     * <p>σ = sample standard deviation. Requires n ≥ 2.</p>
     *
     * @param rewards observed reward values
     * @return estimated volatility, or 0 if n &lt; 2 or constant values
     */
    static double estimateVolatility(double[] rewards) {
        if (rewards.length < 2) {
            return 0.0;
        }

        double mean = 0;
        for (double r : rewards) mean += r;
        mean /= rewards.length;

        double variance = 0;
        for (double r : rewards) {
            double diff = r - mean;
            variance += diff * diff;
        }
        variance /= (rewards.length - 1); // sample variance

        return Math.sqrt(variance);
    }

    /**
     * Deferral decision for a task dispatch.
     *
     * @param shouldDefer whether to defer the task
     * @param optionValue value of the deferral option F(V)
     * @param threshold   V*: optimal execution threshold
     * @param npv         net present value (V - I)
     * @param beta        option exponent β
     * @param reason      human-readable explanation
     */
    public record DeferralDecision(
            boolean shouldDefer,
            double optionValue,
            double threshold,
            double npv,
            double beta,
            String reason
    ) {}

    /**
     * Multi-profile deferral report.
     *
     * @param profileNames     names of evaluated profiles
     * @param decisions        deferral decision per profile
     * @param profilesDeferred count of profiles that should wait
     * @param profilesReady    count of profiles ready to execute
     * @param avgOptionValue   average option value among deferred profiles
     */
    public record RealOptionsReport(
            String[] profileNames,
            DeferralDecision[] decisions,
            int profilesDeferred,
            int profilesReady,
            double avgOptionValue
    ) {}
}
