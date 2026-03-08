package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Indirect coordination of task-to-worker routing using Stigmergy
 * (Grassé 1959; Dorigo's Ant Colony Optimization, 1992).
 *
 * <p>Stigmergy is a mechanism of indirect coordination where agents modify a shared
 * environment (the "pheromone field"), and other agents are influenced by these modifications.
 * There is no direct communication between agents — coordination emerges from the environment.</p>
 *
 * <p>Mapping to the Agent Framework:</p>
 * <ul>
 *   <li><b>Ants</b>: workers completing tasks</li>
 *   <li><b>Pheromone trail</b>: accumulated success signal on the (taskType → workerType) route</li>
 *   <li><b>Deposit</b>: τ[t][w] += Q·reward on task completion with reward &gt; 0</li>
 *   <li><b>Evaporation</b>: τ[t][w] *= (1 − ρ) each update cycle (prevents premature convergence)</li>
 *   <li><b>Task type</b>: inferred from worker_type prefix (e.g., {@code be-*} → {@code be})</li>
 * </ul>
 *
 * <p>Route selection probability: P(w|t) ∝ τ[t][w]^α</p>
 */
@Service
@ConditionalOnProperty(prefix = "stigmergy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StigmergyCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StigmergyCoordinator.class);

    private static final double INITIAL_PHEROMONE = 0.1;
    private static final double CONVERGENCE_EPS   = 0.01;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${stigmergy.evaporation-rate:0.1}")
    private double evaporationRate;

    @Value("${stigmergy.pheromone-alpha:1.0}")
    private double pheromoneAlpha;

    @Value("${stigmergy.deposit-q:1.0}")
    private double depositQ;

    public StigmergyCoordinator(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes the pheromone matrix and recommended routes from historical task outcomes.
     *
     * @return stigmergy report, or {@code null} if no data exists
     */
    public StigmergyReport analyse() {
        List<Object[]> rows = taskOutcomeRepository.findRewardsByWorkerType();
        if (rows.isEmpty()) return null;

        // Collect all worker types and infer task types from prefix
        Map<String, Set<String>> taskToWorkers = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String workerType = (String) row[0];
            String taskType   = extractTaskType(workerType);
            taskToWorkers.computeIfAbsent(taskType, k -> new LinkedHashSet<>()).add(workerType);
        }

        // ── Initialise pheromone matrix ──────────────────────────────────
        Map<String, Map<String, Double>> tau = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : taskToWorkers.entrySet()) {
            Map<String, Double> workerMap = new LinkedHashMap<>();
            for (String worker : e.getValue()) {
                workerMap.put(worker, INITIAL_PHEROMONE);
            }
            tau.put(e.getKey(), workerMap);
        }

        // ── Deposit pheromone from historical rewards ────────────────────
        for (Object[] row : rows) {
            String workerType = (String) row[0];
            double reward     = ((Number) row[1]).doubleValue();
            if (reward <= 0.0) continue;
            String taskType = extractTaskType(workerType);
            tau.computeIfAbsent(taskType, k -> new LinkedHashMap<>())
               .merge(workerType, depositQ * reward, Double::sum);
        }

        // ── Evaporation: one cycle ───────────────────────────────────────
        for (Map<String, Double> workerMap : tau.values()) {
            workerMap.replaceAll((w, t) -> t * (1.0 - evaporationRate));
        }

        // ── Recommended route: argmax pheromone per task type ────────────
        Map<String, String> recommendedRoutes = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : tau.entrySet()) {
            e.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(best -> recommendedRoutes.put(e.getKey(), best.getKey()));
        }

        // ── Convergence: max deviation from mean < ε ─────────────────────
        boolean convergenceDetected = tau.values().stream().allMatch(workerMap -> {
            if (workerMap.size() <= 1) return true;
            double mean   = workerMap.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double maxDev = workerMap.values().stream().mapToDouble(v -> Math.abs(v - mean)).max().orElse(0.0);
            return maxDev < CONVERGENCE_EPS;
        });

        // ── Top routes (readable summary) ────────────────────────────────
        List<String> topRoutes = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> e : tau.entrySet()) {
            e.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(best -> topRoutes.add(
                            e.getKey() + " \u2192 " + best.getKey()
                            + " (\u03c4=" + String.format("%.3f", best.getValue()) + ")"));
        }
        Collections.sort(topRoutes);

        log.debug("Stigmergy: taskTypes={} routes={} convergence={}",
                tau.size(), recommendedRoutes, convergenceDetected);

        return new StigmergyReport(tau, recommendedRoutes, evaporationRate, convergenceDetected, topRoutes);
    }

    /** Infers task type from worker type prefix: "be-java" → "be", "dba-mongo" → "dba". */
    static String extractTaskType(String workerType) {
        int dash = workerType.indexOf('-');
        return dash > 0 ? workerType.substring(0, dash) : workerType;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Stigmergy coordination report.
     *
     * @param pheromoneMatrix     τ[taskType][workerType] after deposit and evaporation
     * @param recommendedRoutes   argmax worker per task type
     * @param evaporationRate     ρ used in this cycle
     * @param convergenceDetected true if pheromone distribution variance &lt; ε for all task types
     * @param topRoutes           human-readable top-pheromone routes
     */
    public record StigmergyReport(
            Map<String, Map<String, Double>> pheromoneMatrix,
            Map<String, String> recommendedRoutes,
            double evaporationRate,
            boolean convergenceDetected,
            List<String> topRoutes
    ) {}
}
