package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ContractTheory.*;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates SLA contracts for worker profiles using Contract Theory.
 *
 * <p>Loads historical task outcomes for each profile of a worker type,
 * calibrates optimal contracts, evaluates performance against contracts,
 * and produces a system-level report with incentive recommendations.</p>
 *
 * <p>This is an analytics-only service — it does NOT modify the dispatch
 * hot path. The evaluation report is exposed via REST endpoint.</p>
 *
 * @see ContractTheory
 * @see <a href="https://www.nobelprize.org/prizes/economic-sciences/2016/summary/">
 *     Hart &amp; Holmström (2016), Nobel Prize in Economics — Contract Theory</a>
 */
@Service
@ConditionalOnProperty(prefix = "contract-theory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContractTheoryService {

    private static final Logger log = LoggerFactory.getLogger(ContractTheoryService.class);

    static final int MAX_OUTCOMES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${contract-theory.default-quality-target:0.6}")
    private double defaultQualityTarget;

    @Value("${contract-theory.default-bonus-multiplier:1.5}")
    private double defaultBonusMultiplier;

    @Value("${contract-theory.default-penalty-rate:0.5}")
    private double defaultPenaltyRate;

    @Value("${contract-theory.learning-rate:0.1}")
    private double learningRate;

    public ContractTheoryService(TaskOutcomeRepository taskOutcomeRepository,
                                  WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Evaluates contracts for all profiles of a given worker type.
     *
     * <ol>
     *   <li>Enumerate profiles via registry</li>
     *   <li>Load recent outcomes per profile</li>
     *   <li>Extract actual_reward (quality) and eloAtDispatch (token cost proxy)</li>
     *   <li>Calibrate optimal contract from observations</li>
     *   <li>Evaluate latest quality against the contract</li>
     *   <li>Aggregate into system-level report with recommendations</li>
     * </ol>
     *
     * @param workerType worker type name (e.g. "BE", "FE")
     * @return evaluation report, or null if no profiles have data
     */
    public ContractEvaluationReport evaluateContracts(String workerType) {
        List<String> candidates = profileRegistry.profilesForWorkerType(
                WorkerType.valueOf(workerType));

        Map<String, double[]> profileQualities = new LinkedHashMap<>();
        Map<String, double[]> profileCosts = new LinkedHashMap<>();

        for (String profile : candidates) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_OUTCOMES);

            if (data.isEmpty()) continue;

            List<Double> qualities = new ArrayList<>();
            List<Double> costs = new ArrayList<>();
            for (Object[] row : data) {
                Double reward = row[10] != null ? ((Number) row[10]).doubleValue() : null;
                Double cost = row[7] != null ? ((Number) row[7]).doubleValue() : null;
                if (reward != null) {
                    qualities.add(reward);
                    costs.add(cost != null ? cost : 1.0);
                }
            }

            if (!qualities.isEmpty()) {
                profileQualities.put(profile,
                        qualities.stream().mapToDouble(Double::doubleValue).toArray());
                profileCosts.put(profile,
                        costs.stream().mapToDouble(Double::doubleValue).toArray());
            }
        }

        if (profileQualities.isEmpty()) {
            log.debug("Contract Theory for {}: no profiles with data", workerType);
            return null;
        }

        String[] profileNames = profileQualities.keySet().toArray(new String[0]);
        int n = profileNames.length;
        WorkerContract[] contracts = new WorkerContract[n];
        ContractEvaluation[] evaluations = new ContractEvaluation[n];
        double systemSurplus = 0.0;
        int aboveTarget = 0;
        int belowTarget = 0;
        List<String> recommendations = new ArrayList<>();
        boolean allIC = true;

        for (int i = 0; i < n; i++) {
            String profile = profileNames[i];
            double[] qualities = profileQualities.get(profile);
            double[] costs = profileCosts.get(profile);

            // Calibrate optimal contract from observations
            contracts[i] = ContractTheory.optimalContract(
                    workerType, profile, qualities, costs,
                    defaultBonusMultiplier, defaultPenaltyRate);

            // Evaluate using the most recent quality observation
            double latestQuality = qualities[qualities.length - 1];
            evaluations[i] = ContractTheory.evaluate(contracts[i], latestQuality);

            systemSurplus += evaluations[i].surplus();

            if (evaluations[i].targetMet()) {
                aboveTarget++;
            } else {
                belowTarget++;
                recommendations.add("Profile '" + profile +
                        "': below target (quality=" +
                        String.format("%.3f", evaluations[i].actualQuality()) +
                        " < target=" +
                        String.format("%.3f", evaluations[i].qualityTarget()) + ")");
            }

            // Check incentive compatibility
            if (!ContractTheory.incentiveCompatible(
                    contracts[i].bonusMultiplier(), contracts[i].penaltyRate())) {
                allIC = false;
                recommendations.add("Profile '" + profile +
                        "': incentive incompatible (bonus=" +
                        String.format("%.2f", contracts[i].bonusMultiplier()) +
                        " <= penalty=" +
                        String.format("%.2f", contracts[i].penaltyRate()) + ")");
            }
        }

        ContractReport report = new ContractReport(
                contracts, evaluations, systemSurplus,
                aboveTarget, belowTarget,
                recommendations.toArray(new String[0]));

        log.debug("Contract Theory for {}: {} profiles, above={}, below={}, IC={}",
                workerType, n, aboveTarget, belowTarget, allIC);

        return new ContractEvaluationReport(workerType, report, n, allIC);
    }

    /**
     * Contract evaluation report for a worker type.
     *
     * @param workerType                 the worker type analyzed
     * @param contractReport             detailed per-profile contract analysis
     * @param profilesEvaluated          number of profiles with data
     * @param systemIncentiveCompatible  true if all contracts satisfy IC constraint
     */
    public record ContractEvaluationReport(
            String workerType,
            ContractReport contractReport,
            int profilesEvaluated,
            boolean systemIncentiveCompatible
    ) {}
}
