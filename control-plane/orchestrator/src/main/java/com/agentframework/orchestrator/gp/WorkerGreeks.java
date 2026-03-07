package com.agentframework.orchestrator.gp;

/**
 * Black-Scholes-inspired sensitivity metrics ("Greeks") for a worker profile.
 *
 * <p>These are computational metaphors — not financial instruments:
 * <ul>
 *   <li><b>Delta</b> — first derivative of predicted reward w.r.t. task difficulty (embedding perturbation)</li>
 *   <li><b>Gamma</b> — second derivative (convexity of the reward response curve)</li>
 *   <li><b>Vega</b> — sensitivity to prediction uncertainty (sigma perturbation)</li>
 *   <li><b>Theta</b> — learning rate proxy: average reward per match from ELO stats</li>
 * </ul>
 *
 * <p>Risk score: {@code |delta|×0.4 + |gamma|×0.3 + |vega|×0.3} (range: 0..∞, higher = riskier).
 * A score > 0.7 suggests the profile's predicted performance is sensitive to small task variations.</p>
 *
 * @see <a href="https://doi.org/10.1086/260062">
 *     Black &amp; Scholes (1973), Journal of Political Economy</a>
 */
public record WorkerGreeks(
        String workerProfile,
        String workerType,
        double baseMu,
        double baseSigma2,
        double delta,
        double gamma,
        double vega,
        double theta,
        double riskScore
) {
    /**
     * Computes the composite risk score from individual Greeks.
     */
    public static double computeRiskScore(double delta, double gamma, double vega) {
        return Math.abs(delta) * 0.4 + Math.abs(gamma) * 0.3 + Math.abs(vega) * 0.3;
    }
}
