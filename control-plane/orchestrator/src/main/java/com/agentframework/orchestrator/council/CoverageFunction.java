package com.agentframework.orchestrator.council;

import java.util.Set;

/**
 * A submodular coverage function for greedy maximisation.
 *
 * <p>A function f is submodular if it satisfies the diminishing returns property:
 * {@code f(S ∪ {x}) - f(S) ≥ f(T ∪ {x}) - f(T)} whenever {@code S ⊆ T}.
 * This guarantees that the greedy algorithm achieves at least (1 - 1/e) ≈ 63%
 * of the optimal solution.</p>
 *
 * @param <T> type of elements being selected
 * @see SubmodularSelector
 * @see <a href="https://doi.org/10.1007/BF01588971">
 *     Nemhauser, Wolsey &amp; Fisher (1978)</a>
 */
@FunctionalInterface
public interface CoverageFunction<T> {

    /**
     * Computes the marginal gain of adding {@code candidate} to the existing {@code selected} set.
     *
     * @param selected  elements already selected
     * @param candidate element being considered for addition
     * @return non-negative marginal gain; 0 if candidate adds no new coverage
     */
    double marginalGain(Set<T> selected, T candidate);
}
