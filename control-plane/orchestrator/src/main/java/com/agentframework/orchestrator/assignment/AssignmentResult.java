package com.agentframework.orchestrator.assignment;

import com.agentframework.gp.model.GpPrediction;

import java.util.List;
import java.util.Map;

/**
 * Result of a global task-to-profile assignment via Hungarian Algorithm (#42).
 *
 * <p>Produced by {@link GlobalAssignmentSolver} and consumed by
 * {@code OrchestrationService} (for dispatch) and {@code PlanController}
 * (for the preview endpoint).</p>
 *
 * @param assignments   taskKey -> assigned profile name
 * @param predictions   taskKey -> GP prediction for the assigned (task, profile) pair
 * @param totalCost     sum of negated mu values (lower = better global assignment)
 * @param criticalPath  task keys on the DAG critical path
 * @param details       per-task breakdown for the preview endpoint
 */
public record AssignmentResult(
    Map<String, String> assignments,
    Map<String, GpPrediction> predictions,
    double totalCost,
    List<String> criticalPath,
    List<TaskAssignmentDetail> details
) {

    /**
     * Per-task detail within a global assignment result.
     *
     * @param taskKey         plan item task key
     * @param workerType      worker type (BE, FE, etc.)
     * @param assignedProfile profile selected by the algorithm
     * @param mu              GP predicted reward for the assigned pair
     * @param sigma2          GP uncertainty for the assigned pair
     * @param onCriticalPath  whether the task is on the DAG critical path
     * @param boosted         whether critical-path boost was applied to mu
     */
    public record TaskAssignmentDetail(
        String taskKey,
        String workerType,
        String assignedProfile,
        double mu,
        double sigma2,
        boolean onCriticalPath,
        boolean boosted
    ) {}
}
