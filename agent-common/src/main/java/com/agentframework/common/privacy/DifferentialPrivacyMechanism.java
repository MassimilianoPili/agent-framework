package com.agentframework.common.privacy;

/**
 * SPI for epsilon-differential privacy mechanisms (#43).
 *
 * <p>A mechanism satisfies (ε)-differential privacy if, for any two datasets
 * D and D' differing in at most one record, and for any set of outputs S:
 * {@code Pr[M(D) ∈ S] ≤ e^ε × Pr[M(D') ∈ S]}.</p>
 *
 * <p>Implementations add calibrated noise to true values before sharing them
 * with federated peers, preventing reverse-engineering of individual task
 * outcomes from aggregated metrics.</p>
 *
 * @see LaplaceMechanism
 * @see PrivacyBudget
 */
public interface DifferentialPrivacyMechanism {

    /**
     * Adds calibrated noise to a true value.
     *
     * @param trueValue   the actual metric value
     * @param sensitivity the maximum change in output caused by a single record
     *                    (e.g. 32.0 for ELO K-factor, 2.0 for reward in [-1,+1])
     * @param epsilon     privacy parameter — smaller ε = more noise = more privacy
     * @return the privatised value (trueValue + noise)
     * @throws IllegalArgumentException if epsilon ≤ 0 or sensitivity < 0
     */
    double privatize(double trueValue, double sensitivity, double epsilon);

    /**
     * Computes the remaining privacy budget after {@code queriesUsed} queries.
     *
     * <p>Under basic sequential composition (Dwork et al. 2006), the total
     * privacy cost of K queries each at ε is Kε. Advanced composition
     * (Dwork 2010) reduces this to O(√K · ε) but is not yet implemented.</p>
     *
     * @param initialEpsilon the per-query privacy parameter
     * @param queriesUsed    number of queries already executed
     * @return remaining budget (≥ 0); 0 means budget is exhausted
     */
    double remainingBudget(double initialEpsilon, int queriesUsed);
}
