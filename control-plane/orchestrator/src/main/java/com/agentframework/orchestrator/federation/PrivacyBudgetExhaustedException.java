package com.agentframework.orchestrator.federation;

/**
 * Thrown when a differential privacy query is attempted but the daily budget
 * is exhausted (#43).
 *
 * <p>The caller should either wait until the next UTC day (when the budget
 * auto-resets) or reduce the frequency of metric exports.</p>
 */
public class PrivacyBudgetExhaustedException extends RuntimeException {

    public PrivacyBudgetExhaustedException(String message) {
        super(message);
    }
}
