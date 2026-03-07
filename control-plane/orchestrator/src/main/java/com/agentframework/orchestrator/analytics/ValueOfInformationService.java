package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ValueOfInformation.*;
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
 * Evaluates exploration opportunities for worker profiles using Value of Information.
 *
 * <p>Loads historical task outcomes for each profile of a worker type, computes
 * EVSI (Expected Value of Sample Information) to determine whether gathering
 * additional performance data is worth the exploration cost.</p>
 *
 * <p>This is an analytics-only service — it does NOT modify the dispatch
 * hot path. The exploration report is exposed via REST endpoint.</p>
 *
 * @see ValueOfInformation
 * @see <a href="https://doi.org/10.2307/j.ctv36zr3d">
 *     Raiffa &amp; Schlaifer (1961), Applied Statistical Decision Theory</a>
 */
@Service
@ConditionalOnProperty(prefix = "voi", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ValueOfInformationService {

    private static final Logger log = LoggerFactory.getLogger(ValueOfInformationService.class);

    static final int MIN_OUTCOMES = 3;
    static final int MAX_OUTCOMES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${voi.exploration-fraction:0.15}")
    private double explorationFraction;

    @Value("${voi.monte-carlo-samples:500}")
    private int monteCarloSamples;

    public ValueOfInformationService(TaskOutcomeRepository taskOutcomeRepository,
                                      WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Evaluates exploration opportunities for all profiles of a given worker type.
     *
     * <ol>
     *   <li>Enumerate all profiles for the worker type via registry</li>
     *   <li>For each profile, load recent outcomes and compute mean/variance</li>
     *   <li>Estimate sample noise variance from overall outcome variance</li>
     *   <li>Compute EVSI per profile and rank by net VoI</li>
     *   <li>Identify the profile most worth exploring</li>
     * </ol>
     *
     * @param workerType worker type name (e.g. "BE", "FE")
     * @return exploration report, or null if insufficient data
     */
    public VoiExplorationReport evaluateExploration(String workerType) {
        List<String> candidates = profileRegistry.profilesForWorkerType(
                WorkerType.valueOf(workerType));

        Map<String, double[]> profileStats = new LinkedHashMap<>(); // name → [mean, variance]

        double overallSum = 0.0;
        int overallCount = 0;

        for (String profile : candidates) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_OUTCOMES);

            if (data.size() < MIN_OUTCOMES) {
                continue;
            }

            // Extract actual_reward values (index 10)
            List<Double> rewards = new ArrayList<>();
            for (Object[] row : data) {
                Double reward = row[10] != null ? ((Number) row[10]).doubleValue() : null;
                if (reward != null) {
                    rewards.add(reward);
                }
            }

            if (rewards.size() < MIN_OUTCOMES) continue;

            double mean = rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = 0.0;
            for (double r : rewards) {
                double diff = r - mean;
                variance += diff * diff;
            }
            variance = rewards.size() > 1 ? variance / (rewards.size() - 1) : 0.0;

            profileStats.put(profile, new double[]{mean, variance});
            overallSum += rewards.stream().mapToDouble(Double::doubleValue).sum();
            overallCount += rewards.size();
        }

        if (profileStats.isEmpty()) {
            log.debug("VoI analysis for {}: no profiles with sufficient data", workerType);
            return null;
        }

        // Estimate sample noise variance (conservative: average of per-profile variances)
        double sampleNoiseVariance = profileStats.values().stream()
                .mapToDouble(s -> s[1])
                .average()
                .orElse(0.1);
        if (sampleNoiseVariance < 1e-12) sampleNoiseVariance = 0.01;

        // Exploration cost = fraction of mean token cost
        double meanReward = overallCount > 0 ? overallSum / overallCount : 0.5;
        double explorationCost = explorationFraction * meanReward;

        // Compute EVSI and net VoI per profile
        String[] profileNames = profileStats.keySet().toArray(new String[0]);
        int n = profileNames.length;
        double[] evsiValues = new double[n];
        double[] netVoiValues = new double[n];
        double[] priorSigma2s = new double[n];

        for (int i = 0; i < n; i++) {
            priorSigma2s[i] = profileStats.get(profileNames[i])[1];
            evsiValues[i] = ValueOfInformation.evsiNormalNormal(priorSigma2s[i], sampleNoiseVariance);
            netVoiValues[i] = ValueOfInformation.netVoi(evsiValues[i], explorationCost);
        }

        int[] ranking = ValueOfInformation.rankByVoi(priorSigma2s, sampleNoiseVariance, explorationCost);

        // Total exploration value = sum of positive net VoI
        double totalExplorationValue = 0.0;
        for (double nv : netVoiValues) {
            if (nv > 0) totalExplorationValue += nv;
        }

        int recommendedTarget = ranking.length > 0 ? ranking[0] : 0;

        VoiReport voiReport = new VoiReport(
                profileNames, evsiValues, netVoiValues, ranking,
                recommendedTarget, totalExplorationValue);

        log.debug("VoI analysis for {}: {} profiles, recommended exploration='{}', " +
                        "total exploration value={}",
                workerType, n,
                profileNames[recommendedTarget],
                String.format("%.4f", totalExplorationValue));

        return new VoiExplorationReport(workerType, voiReport, explorationFraction, n);
    }

    /**
     * VoI exploration report for a worker type.
     *
     * @param workerType               the worker type analyzed
     * @param voiReport                VoI analysis results
     * @param explorationBudgetFraction configured exploration budget fraction
     * @param profilesEvaluated        number of profiles with sufficient data
     */
    public record VoiExplorationReport(
            String workerType,
            VoiReport voiReport,
            double explorationBudgetFraction,
            int profilesEvaluated
    ) {}
}
