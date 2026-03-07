package com.agentframework.orchestrator.budget;

/**
 * Kelly Criterion for optimal budget fraction sizing.
 *
 * <p>Full Kelly: {@code f* = (p·b - q·a) / (a·b)} where p = win probability,
 * q = 1-p, b = win payoff, a = loss payoff (positive number).
 * Half-Kelly: f&#42;/2 (conservative, reduces variance at cost of lower growth).</p>
 *
 * <p>The Kelly fraction maximizes the expected logarithmic growth rate of wealth.
 * In the agent framework context, it determines what fraction of the token budget
 * to allocate to a specific worker profile based on its historical win rate.</p>
 *
 * @see <a href="https://doi.org/10.1002/j.1538-7305.1956.tb03809.x">
 *     Kelly (1956), A New Interpretation of Information Rate,
 *     Bell System Technical Journal 35(4)</a>
 */
public final class KellyCriterion {

    /** Maximum budget fraction to prevent over-concentration. */
    static final double DEFAULT_MAX_FRACTION = 0.5;

    private KellyCriterion() {}

    /**
     * Full Kelly fraction.
     *
     * <p>{@code f* = (p·b - q·a) / (a·b)}</p>
     *
     * <p>Guard: if a·b = 0, returns 0. If f* &lt; 0 (negative edge), returns 0.</p>
     *
     * @param winProb   probability of winning (p)
     * @param winPayoff payoff on win (b, positive)
     * @param lossPayoff payoff on loss (a, positive — the amount you lose)
     * @return optimal fraction in [0, ∞), or 0 if no edge
     */
    static double fullKelly(double winProb, double winPayoff, double lossPayoff) {
        if (winPayoff <= 0 || lossPayoff <= 0) return 0.0;
        double q = 1.0 - winProb;
        double f = (winProb * winPayoff - q * lossPayoff) / (winPayoff * lossPayoff);
        return Math.max(0.0, f);
    }

    /**
     * Half-Kelly: half the full Kelly fraction.
     *
     * <p>Reduces variance by ~75% at the cost of ~25% lower expected growth.
     * Widely used in practice because the true edge is estimated, not known.</p>
     */
    static double halfKelly(double winProb, double winPayoff, double lossPayoff) {
        return fullKelly(winProb, winPayoff, lossPayoff) / 2.0;
    }

    /**
     * Fractional Kelly: scales the full Kelly by a given fraction.
     *
     * @param fraction scaling factor (e.g. 0.5 for half-Kelly, 0.25 for quarter-Kelly)
     */
    static double fractionalKelly(double winProb, double winPayoff, double lossPayoff,
                                  double fraction) {
        return fullKelly(winProb, winPayoff, lossPayoff) * fraction;
    }

    /**
     * Clamps a fraction to the maximum allowed.
     *
     * @param f           computed fraction
     * @param maxFraction safety cap
     * @return min(f, maxFraction)
     */
    static double clamp(double f, double maxFraction) {
        return Math.min(f, maxFraction);
    }

    /**
     * Complete Kelly computation with fraction and clamping.
     *
     * @param winProb     win probability
     * @param winPayoff   payoff on win
     * @param lossPayoff  payoff on loss
     * @param fraction    fractional Kelly multiplier (e.g. 0.5)
     * @param maxFraction maximum allowed fraction
     * @return full computation result
     */
    static KellyRecommendation compute(double winProb, double winPayoff, double lossPayoff,
                                       double fraction, double maxFraction) {
        double full = fullKelly(winProb, winPayoff, lossPayoff);
        double adjusted = full * fraction;
        double clamped = clamp(adjusted, maxFraction);
        boolean shouldBet = full > 0.0;
        return new KellyRecommendation(full, adjusted, clamped, shouldBet);
    }

    /**
     * Result of Kelly criterion computation.
     *
     * @param fullKellyFraction  raw f* from Kelly formula
     * @param adjustedFraction   f* × fractional multiplier
     * @param clampedFraction    final fraction after safety cap
     * @param shouldBet          true if there's a positive edge (f* &gt; 0)
     */
    public record KellyRecommendation(
            double fullKellyFraction,
            double adjustedFraction,
            double clampedFraction,
            boolean shouldBet
    ) {}
}
