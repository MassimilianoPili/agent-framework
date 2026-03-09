package com.agentframework.orchestrator.assignment;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.config.GlobalAssignmentProperties;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Global task-to-profile assignment using the Hungarian Algorithm (#42).
 *
 * <p>Given a batch of dispatchable {@link PlanItem}s and the plan's DAG,
 * this service builds a cost matrix from GP predictions, applies a critical-path
 * boost, and runs the Kuhn-Munkres O(n³) algorithm to find the globally optimal
 * assignment of tasks to worker profiles.</p>
 *
 * <p>Requires GP to be enabled ({@code gp.enabled=true}); this service is only
 * instantiated when {@code global-assignment.enabled=true}.</p>
 */
@Service
@ConditionalOnProperty(prefix = "global-assignment", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GlobalAssignmentProperties.class)
public class GlobalAssignmentSolver {

    private static final Logger log = LoggerFactory.getLogger(GlobalAssignmentSolver.class);

    private final TaskOutcomeService taskOutcomeService;
    private final WorkerProfileRegistry profileRegistry;
    private final CriticalPathCalculator criticalPathCalculator;
    private final GlobalAssignmentProperties properties;

    public GlobalAssignmentSolver(TaskOutcomeService taskOutcomeService,
                                  WorkerProfileRegistry profileRegistry,
                                  CriticalPathCalculator criticalPathCalculator,
                                  GlobalAssignmentProperties properties) {
        this.taskOutcomeService = taskOutcomeService;
        this.profileRegistry = profileRegistry;
        this.criticalPathCalculator = criticalPathCalculator;
        this.properties = properties;
    }

    /**
     * Computes the globally optimal assignment of dispatchable items to worker profiles.
     *
     * @param dispatchable items ready for dispatch (must have size >= minDispatchable)
     * @param plan         the plan (for critical path computation)
     * @return assignment result with per-task profile assignments and predictions
     */
    public AssignmentResult solve(List<PlanItem> dispatchable, Plan plan) {
        // 1. Compute critical path
        Set<String> cpTasks = computeCriticalPathTasks(plan);

        // 2. Collect union of all compatible profiles across dispatchable items
        List<String> allProfiles = collectProfiles(dispatchable);
        if (allProfiles.isEmpty()) {
            log.debug("No profiles found for dispatchable items, returning empty assignment");
            return emptyResult(cpTasks);
        }

        // 3. Build cost matrix: rows = tasks, columns = profiles
        //    cost = -mu (minimize cost = maximize reward)
        //    incompatible pairs = +INF
        double[][] costMatrix = new double[dispatchable.size()][allProfiles.size()];
        Map<String, float[]> embeddingCache = new HashMap<>();
        GpPrediction[][] predictionMatrix = new GpPrediction[dispatchable.size()][allProfiles.size()];

        for (int i = 0; i < dispatchable.size(); i++) {
            PlanItem item = dispatchable.get(i);
            List<String> compatible = profileRegistry.profilesForWorkerType(item.getWorkerType());
            Set<String> compatibleSet = new HashSet<>(compatible);

            // Embed task once (cache across profiles)
            float[] embedding = embeddingCache.computeIfAbsent(item.getTaskKey(),
                    k -> taskOutcomeService.embedTask(item.getTitle(), item.getDescription()));

            boolean onCriticalPath = cpTasks.contains(item.getTaskKey());

            for (int j = 0; j < allProfiles.size(); j++) {
                String profile = allProfiles.get(j);

                if (!compatibleSet.contains(profile)) {
                    costMatrix[i][j] = Double.POSITIVE_INFINITY;
                    continue;
                }

                GpPrediction prediction = taskOutcomeService.predict(
                        embedding, item.getWorkerType().name(), profile);
                predictionMatrix[i][j] = prediction;

                double effectiveMu = prediction.mu();
                if (onCriticalPath) {
                    effectiveMu *= (1.0 + properties.criticalPathBoost());
                }

                // Negate: Hungarian minimizes, we want to maximize mu
                costMatrix[i][j] = -effectiveMu;
            }
        }

        // 4. Run Hungarian Algorithm
        int[] assignment = HungarianAlgorithm.solve(costMatrix);

        // 5. Build result
        return buildResult(dispatchable, allProfiles, assignment, predictionMatrix, cpTasks, costMatrix);
    }

    private Set<String> computeCriticalPathTasks(Plan plan) {
        try {
            return new HashSet<>(criticalPathCalculator.computeSchedule(plan).criticalPath());
        } catch (Exception e) {
            log.debug("Critical path computation failed, proceeding without CP info: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Collects the union of all profile names across the worker types of dispatchable items.
     * Deduplicates and preserves insertion order.
     */
    private List<String> collectProfiles(List<PlanItem> dispatchable) {
        Set<String> seen = new LinkedHashSet<>();
        for (PlanItem item : dispatchable) {
            seen.addAll(profileRegistry.profilesForWorkerType(item.getWorkerType()));
        }
        return new ArrayList<>(seen);
    }

    private AssignmentResult buildResult(List<PlanItem> dispatchable, List<String> allProfiles,
                                          int[] assignment, GpPrediction[][] predictionMatrix,
                                          Set<String> cpTasks, double[][] costMatrix) {
        Map<String, String> assignments = new LinkedHashMap<>();
        Map<String, GpPrediction> predictions = new LinkedHashMap<>();
        List<AssignmentResult.TaskAssignmentDetail> details = new ArrayList<>();
        double totalCost = 0.0;

        for (int i = 0; i < dispatchable.size(); i++) {
            PlanItem item = dispatchable.get(i);
            int col = assignment[i];

            // Skip dummy column assignments (col == -1 or col >= allProfiles.size())
            if (col < 0 || col >= allProfiles.size()) {
                continue;
            }

            String profile = allProfiles.get(col);
            GpPrediction prediction = predictionMatrix[i][col];

            // Skip incompatible assignments (should not happen, but guard)
            if (prediction == null || costMatrix[i][col] == Double.POSITIVE_INFINITY) {
                continue;
            }

            assignments.put(item.getTaskKey(), profile);
            predictions.put(item.getTaskKey(), prediction);
            totalCost += costMatrix[i][col];

            boolean onCp = cpTasks.contains(item.getTaskKey());
            details.add(new AssignmentResult.TaskAssignmentDetail(
                    item.getTaskKey(),
                    item.getWorkerType().name(),
                    profile,
                    prediction.mu(),
                    prediction.sigma2(),
                    onCp,
                    onCp && properties.criticalPathBoost() > 0
            ));
        }

        log.info("Global assignment: {} tasks → {} profiles, totalCost={}, cpTasks={}",
                dispatchable.size(), assignments.size(),
                String.format("%.4f", totalCost), cpTasks.size());

        return new AssignmentResult(
                assignments,
                predictions,
                totalCost,
                cpTasks.stream().sorted().collect(Collectors.toList()),
                details
        );
    }

    private AssignmentResult emptyResult(Set<String> cpTasks) {
        return new AssignmentResult(
                Map.of(), Map.of(), 0.0,
                cpTasks.stream().sorted().collect(Collectors.toList()),
                List.of()
        );
    }
}
