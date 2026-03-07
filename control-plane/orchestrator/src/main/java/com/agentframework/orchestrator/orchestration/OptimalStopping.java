package com.agentframework.orchestrator.orchestration;

/**
 * Optimal Stopping (Secretary Problem) — threshold-based acceptance using the 1/e rule.
 *
 * <p>Algorithm: observe the first N/e candidates (observation phase), record the
 * maximum reward seen, then accept the first subsequent candidate that exceeds
 * this threshold. This achieves a ~36.8% probability of selecting the best candidate,
 * which is provably optimal.</p>
 *
 * @see <a href="https://doi.org/10.1214/ss/1177012493">
 *     Ferguson (1989), Who Solved the Secretary Problem?, Statistical Science 4(3)</a>
 * @see <a href="https://doi.org/10.2307/2985407">
 *     Lindley (1961), Dynamic Programming and Decision Theory, Applied Statistics 10(1)</a>
 */
public final class OptimalStopping {

    /** 1/e ≈ 0.3679 — the optimal observation fraction. */
    static final double ONE_OVER_E = 1.0 / Math.E;

    /** Default observation fraction (1/e). */
    static final double DEFAULT_OBSERVATION_FRACTION = ONE_OVER_E;

    private OptimalStopping() {}

    /**
     * Computes the observation phase size.
     *
     * @param totalCandidates    total number of candidates
     * @param observationFraction fraction to observe (typically 1/e)
     * @return observation size, minimum 1
     */
    static int observationSize(int totalCandidates, double observationFraction) {
        return Math.max(1, (int) Math.floor(totalCandidates * observationFraction));
    }

    /**
     * Computes the threshold from observation phase rewards.
     *
     * @param observationRewards rewards seen during observation phase
     * @return maximum reward seen, or 0.0 if empty
     */
    static double threshold(double[] observationRewards) {
        if (observationRewards.length == 0) return 0.0;
        double max = observationRewards[0];
        for (int i = 1; i < observationRewards.length; i++) {
            if (observationRewards[i] > max) max = observationRewards[i];
        }
        return max;
    }

    /**
     * Determines whether to accept a candidate.
     *
     * @param candidateReward the candidate's reward
     * @param threshold       the threshold from observation phase
     * @return true if candidateReward exceeds the threshold
     */
    static boolean shouldAccept(double candidateReward, double threshold) {
        return candidateReward > threshold;
    }

    /**
     * Complete optimal stopping evaluation.
     *
     * <p>Splits historical rewards into observation and decision phases,
     * computes the threshold, and evaluates the candidate.</p>
     *
     * @param historicalRewards all historical rewards (chronological order)
     * @param candidateReward   the new candidate's expected reward
     * @param observationFraction fraction of history to use as observation phase
     * @return stopping decision
     */
    static StoppingDecision evaluate(double[] historicalRewards, double candidateReward,
                                     double observationFraction) {
        int total = historicalRewards.length;
        int obsSize = observationSize(total, observationFraction);

        // Observation phase: first obsSize rewards
        double[] obsRewards = new double[Math.min(obsSize, total)];
        System.arraycopy(historicalRewards, 0, obsRewards, 0, obsRewards.length);

        double thresh = threshold(obsRewards);
        boolean accept = shouldAccept(candidateReward, thresh);

        return new StoppingDecision(thresh, obsSize, total, accept, candidateReward);
    }

    /**
     * Result of optimal stopping evaluation.
     *
     * @param threshold       maximum reward from observation phase
     * @param observationSize number of candidates in observation phase
     * @param totalCandidates total historical candidates
     * @param shouldAccept    whether the candidate should be accepted
     * @param candidateReward the evaluated candidate's reward
     */
    public record StoppingDecision(
            double threshold,
            int observationSize,
            int totalCandidates,
            boolean shouldAccept,
            double candidateReward
    ) {}
}
