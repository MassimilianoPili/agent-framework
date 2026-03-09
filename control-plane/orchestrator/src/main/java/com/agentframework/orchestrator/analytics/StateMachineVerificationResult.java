package com.agentframework.orchestrator.analytics;

import java.util.List;

/**
 * Result of exhaustive state machine model checking (#38).
 *
 * <p>Contains per-property verification results (P1-P6) with counterexamples
 * for violated properties. Used at compile-time (JUnit) to prove state machine
 * properties hold across the entire reachable state space.</p>
 *
 * @param properties    per-property results (P1-P6)
 * @param allSatisfied  true if all properties are satisfied
 * @param reachableStates number of states explored by BFS
 */
public record StateMachineVerificationResult(
        List<PropertyResult> properties,
        boolean allSatisfied,
        int reachableStates
) {

    /**
     * Result of a single temporal property check.
     *
     * @param id            property identifier (e.g., "P1")
     * @param description   human-readable formula description
     * @param satisfied     true if the property holds
     * @param counterexample counterexample state if violated, null otherwise
     */
    public record PropertyResult(
            String id,
            String description,
            boolean satisfied,
            String counterexample
    ) {
        public static PropertyResult satisfied(String id, String description) {
            return new PropertyResult(id, description, true, null);
        }

        public static PropertyResult violated(String id, String description, String counterexample) {
            return new PropertyResult(id, description, false, counterexample);
        }
    }

    public static StateMachineVerificationResult of(List<PropertyResult> properties, int reachableStates) {
        boolean all = properties.stream().allMatch(PropertyResult::satisfied);
        return new StateMachineVerificationResult(properties, all, reachableStates);
    }
}
