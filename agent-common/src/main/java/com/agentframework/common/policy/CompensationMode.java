package com.agentframework.common.policy;

/**
 * Semantic intent of a plan-level compensation operation.
 *
 * <p>Passed to {@code OrchestrationService.compensatePlan()} to unambiguously
 * describe what should happen when a completed or failed plan is re-opened.</p>
 *
 * <table>
 *   <caption>Mode semantics</caption>
 *   <tr><th>Mode</th><th>Intended for</th><th>Effect</th></tr>
 *   <tr><td>UNDO</td><td>Saga rollback</td>
 *       <td>Creates a COMPENSATOR_MANAGER task for every DONE item, asking the
 *           worker to reverse its effects (file deletions, DB mutations, etc.)</td></tr>
 *   <tr><td>RETRY</td><td>Selective re-run</td>
 *       <td>Resets every FAILED item back to WAITING and re-enters the
 *           dispatch loop — no new tasks are created.</td></tr>
 *   <tr><td>AMENDMENT</td><td>Adding new work to a finished plan</td>
 *       <td>Re-opens the plan (COMPLETED/FAILED → RUNNING) without touching
 *           existing items. The caller is expected to POST new items separately.</td></tr>
 * </table>
 */
public enum CompensationMode {

    /**
     * Saga undo: create a COMPENSATOR_MANAGER task for each DONE item so the
     * worker can reverse the side effects it produced.
     */
    UNDO,

    /**
     * Selective retry: reset every FAILED item to WAITING and redispatch.
     * Useful when transient failures (network timeouts, OOM) caused the plan to fail.
     */
    RETRY,

    /**
     * Amendment: re-open a terminal plan so the operator can add new tasks.
     * Existing DONE items are left untouched.
     */
    AMENDMENT
}
