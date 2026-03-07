package com.agentframework.orchestrator.analytics;

/**
 * Prospect Theory value and probability weighting functions.
 *
 * <p>Value function: {@code v(x) = x^α} for gains, {@code v(x) = -λ(-x)^β} for losses.
 * Probability weighting: {@code w(p) = p^γ / (p^γ + (1-p)^γ)^(1/γ)}.
 * Prospect value: {@code V = Σ w(pᵢ) × v(xᵢ)}.</p>
 *
 * <p>Default parameters from Tversky &amp; Kahneman (1992):
 * α=0.88, β=0.88, λ=2.25, γ_gain=0.61, γ_loss=0.69.</p>
 *
 * @see <a href="https://doi.org/10.2307/1914185">
 *     Kahneman &amp; Tversky (1979), Prospect Theory, Econometrica 47(2)</a>
 * @see <a href="https://doi.org/10.1007/BF00122574">
 *     Tversky &amp; Kahneman (1992), Advances in Prospect Theory, J. Risk and Uncertainty 5(4)</a>
 */
public final class ProspectTheory {

    // Tversky & Kahneman (1992) calibrated parameters
    static final double DEFAULT_ALPHA = 0.88;
    static final double DEFAULT_BETA = 0.88;
    static final double DEFAULT_LAMBDA = 2.25;
    static final double DEFAULT_GAMMA_GAIN = 0.61;
    static final double DEFAULT_GAMMA_LOSS = 0.69;

    private ProspectTheory() {}

    /**
     * Piecewise value function.
     *
     * <p>{@code v(x) = x^α} for x ≥ 0 (diminishing sensitivity to gains),
     * {@code v(x) = -λ(-x)^β} for x &lt; 0 (loss aversion).</p>
     *
     * @param x      outcome (positive = gain, negative = loss)
     * @param alpha  curvature for gains (0 &lt; α &lt; 1 → risk aversion for gains)
     * @param beta   curvature for losses (0 &lt; β &lt; 1 → risk seeking for losses)
     * @param lambda loss aversion coefficient (λ &gt; 1 → losses weigh more)
     * @return subjective value
     */
    static double value(double x, double alpha, double beta, double lambda) {
        if (x == 0) return 0.0;
        if (x > 0) {
            return Math.pow(x, alpha);
        } else {
            return -lambda * Math.pow(-x, beta);
        }
    }

    /** Value function with default parameters (α=0.88, β=0.88, λ=2.25). */
    static double value(double x) {
        return value(x, DEFAULT_ALPHA, DEFAULT_BETA, DEFAULT_LAMBDA);
    }

    /**
     * Probability weighting function (Prelec-style).
     *
     * <p>{@code w(p) = p^γ / (p^γ + (1-p)^γ)^(1/γ)}</p>
     *
     * <p>Overweights small probabilities, underweights large probabilities.</p>
     *
     * @param p     probability in [0, 1]
     * @param gamma curvature parameter (γ &lt; 1 → inverse S-shaped)
     * @return weighted probability
     */
    static double weightProbability(double p, double gamma) {
        if (p <= 0.0) return 0.0;
        if (p >= 1.0) return 1.0;
        double pg = Math.pow(p, gamma);
        double qg = Math.pow(1.0 - p, gamma);
        return pg / Math.pow(pg + qg, 1.0 / gamma);
    }

    /**
     * Prospect value: {@code V = Σ w(pᵢ) × v(xᵢ)}.
     *
     * <p>Separates gains and losses to apply different gamma parameters:
     * γ_gain for positive outcomes, γ_loss for negative outcomes.</p>
     *
     * @param outcomes      array of possible outcomes (positive = gains, negative = losses)
     * @param probabilities array of probabilities (same length as outcomes)
     * @return subjective prospect value
     */
    static double prospectValue(double[] outcomes, double[] probabilities,
                                double alpha, double beta, double lambda,
                                double gammaGain, double gammaLoss) {
        if (outcomes.length != probabilities.length) {
            throw new IllegalArgumentException("outcomes and probabilities must have same length");
        }
        double total = 0.0;
        for (int i = 0; i < outcomes.length; i++) {
            double v = value(outcomes[i], alpha, beta, lambda);
            double gamma = outcomes[i] >= 0 ? gammaGain : gammaLoss;
            double w = weightProbability(probabilities[i], gamma);
            total += w * v;
        }
        return total;
    }

    /** Prospect value with default parameters. */
    static double prospectValue(double[] outcomes, double[] probabilities) {
        return prospectValue(outcomes, probabilities,
                DEFAULT_ALPHA, DEFAULT_BETA, DEFAULT_LAMBDA,
                DEFAULT_GAMMA_GAIN, DEFAULT_GAMMA_LOSS);
    }

    /**
     * Evaluation result for a worker profile.
     *
     * @param profile             worker profile identifier
     * @param prospectValue       subjective value (prospect theory)
     * @param rawExpectedValue    objective expected value (no behavioral bias)
     * @param lossAversionPenalty difference: rawExpectedValue - prospectValue (positive = penalty)
     */
    public record ProspectEvaluation(
            String profile,
            double prospectValue,
            double rawExpectedValue,
            double lossAversionPenalty
    ) {}
}
