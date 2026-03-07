package com.agentframework.orchestrator.council;

import java.util.*;

/**
 * Greedy submodular maximisation with CELF (Cost-Effective Lazy Forward) optimisation.
 *
 * <p>Selects up to k elements from a candidate set to maximise a submodular coverage function.
 * The greedy algorithm provides a provable (1 - 1/e) ≈ 63% approximation guarantee for
 * monotone submodular functions.</p>
 *
 * <p>CELF exploits the diminishing returns property: when we add element x to a larger set,
 * its marginal gain can only decrease. Therefore, we can skip re-evaluating candidates whose
 * upper bound (previous marginal gain) is already below the current best gain. This typically
 * yields a 5-10x speedup over naive greedy.</p>
 *
 * @param <T> type of elements being selected
 * @see CoverageFunction
 * @see <a href="https://doi.org/10.1007/BF01588971">
 *     Nemhauser, Wolsey &amp; Fisher (1978)</a>
 * @see <a href="https://doi.org/10.1145/1281192.1281239">
 *     Leskovec et al. (2007), CELF</a>
 */
public class SubmodularSelector<T> {

    /**
     * Selects up to {@code k} elements from {@code candidates} that maximise the
     * given submodular coverage function using CELF lazy evaluation.
     *
     * @param candidates all candidate elements
     * @param k          maximum number of elements to select
     * @param function   submodular coverage function
     * @return ordered list of selected elements (in order of selection)
     */
    public List<T> select(Collection<T> candidates, int k, CoverageFunction<T> function) {
        if (candidates.isEmpty() || k <= 0) {
            return List.of();
        }

        Set<T> selected = new LinkedHashSet<>();
        List<T> result = new ArrayList<>();

        // Priority queue ordered by marginal gain (descending)
        PriorityQueue<CelfEntry<T>> pq = new PriorityQueue<>(
                Comparator.comparingDouble((CelfEntry<T> e) -> e.gain).reversed());

        // Initial evaluation: compute marginal gain for all candidates with empty set
        for (T candidate : candidates) {
            double gain = function.marginalGain(Set.of(), candidate);
            pq.add(new CelfEntry<>(candidate, gain, 0));
        }

        int round = 0;
        while (result.size() < k && !pq.isEmpty()) {
            round++;

            // Find the element with the highest (lazy) marginal gain
            while (true) {
                CelfEntry<T> top = pq.poll();
                if (top == null) return result;

                if (top.lastEvaluated == round) {
                    // This entry was already re-evaluated in this round — select it
                    if (top.gain <= 0) {
                        return result; // no more positive gain
                    }
                    selected.add(top.element);
                    result.add(top.element);
                    break;
                }

                // Re-evaluate with the current selected set
                double newGain = function.marginalGain(selected, top.element);
                pq.add(new CelfEntry<>(top.element, newGain, round));
            }

            // Remove selected elements from the queue
            pq.removeIf(e -> selected.contains(e.element));
        }

        return result;
    }

    /**
     * Entry in the CELF priority queue, tracking the element, its last computed
     * marginal gain, and the round when it was last evaluated.
     */
    private record CelfEntry<T>(T element, double gain, int lastEvaluated) {}
}
