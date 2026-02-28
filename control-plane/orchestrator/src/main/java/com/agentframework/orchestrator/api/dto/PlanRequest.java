package com.agentframework.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record PlanRequest(
    @NotBlank(message = "Specification must not be blank")
    @Size(min = 10, message = "Specification must be at least 10 characters")
    String spec,

    /**
     * Optional token budget for this plan.
     * If null, no budget is enforced.
     */
    Budget budget,

    /**
     * Maximum nesting depth for SUB_PLAN items.
     * A plan at depth {@code maxDepth} cannot spawn further sub-plans.
     * Defaults to 3 if not specified.
     */
    Integer maxDepth
) {
    /**
     * Per-workerType token limits and the enforcement policy when limits are exceeded.
     *
     * <p>Example JSON:
     * <pre>
     * {
     *   "onExceeded": "NO_NEW_DISPATCH",
     *   "perWorkerType": {
     *     "BACKEND_ENGINEER": 50000,
     *     "CONTEXT_MANAGER": 20000
     *   }
     * }
     * </pre>
     *
     * <p>Enforcement modes:
     * <ul>
     *   <li>{@code FAIL_FAST} — dispatch blocked; item transitions to FAILED</li>
     *   <li>{@code NO_NEW_DISPATCH} — dispatch skipped; item stays WAITING</li>
     *   <li>{@code SOFT_LIMIT} — warning logged, dispatch proceeds anyway</li>
     * </ul>
     */
    public record Budget(
        String onExceeded,
        Map<String, Long> perWorkerType
    ) {}
}
