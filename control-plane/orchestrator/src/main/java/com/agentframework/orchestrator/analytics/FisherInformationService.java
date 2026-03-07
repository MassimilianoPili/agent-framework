package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.FisherInformation.*;
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
 * Analyzes uncertainty for worker profiles using Fisher Information metrics.
 *
 * <p>Loads historical task outcomes for each profile of a worker type, computes
 * the uncertainty decomposition (reducible vs irreducible), and identifies
 * which profiles would benefit most from additional exploration.</p>
 *
 * <p>This is an analytics-only service — it does NOT modify the dispatch
 * hot path. The uncertainty report is exposed via REST endpoint.</p>
 *
 * @see FisherInformation
 * @see <a href="https://doi.org/10.1017/S0305004100009580">
 *     Fisher (1925), Theory of Statistical Estimation</a>
 */
@Service
@ConditionalOnProperty(prefix = "fisher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FisherInformationService {

    private static final Logger log = LoggerFactory.getLogger(FisherInformationService.class);

    static final int MAX_OUTCOMES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${fisher.min-observations:5}")
    private int minObservations;

    public FisherInformationService(TaskOutcomeRepository taskOutcomeRepository,
                                     WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Analyzes uncertainty for all profiles of a given worker type.
     *
     * <ol>
     *   <li>Enumerate all profiles for the worker type via registry</li>
     *   <li>For each profile, load recent outcomes and extract actual rewards</li>
     *   <li>Compute uncertainty decomposition per profile</li>
     *   <li>Identify the profile with the most reducible uncertainty</li>
     * </ol>
     *
     * @param workerType worker type name (e.g. "BE", "FE")
     * @return uncertainty report, or null if insufficient data
     */
    public FisherUncertaintyReport analyzeUncertainty(String workerType) {
        List<String> candidates = profileRegistry.profilesForWorkerType(
                WorkerType.valueOf(workerType));

        Map<String, double[]> profileObservations = new LinkedHashMap<>();

        for (String profile : candidates) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_OUTCOMES);

            if (data.size() < minObservations) {
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

            if (rewards.size() >= minObservations) {
                profileObservations.put(profile, rewards.stream()
                        .mapToDouble(Double::doubleValue).toArray());
            }
        }

        if (profileObservations.isEmpty()) {
            log.debug("Fisher analysis for {}: no profiles with sufficient data", workerType);
            return null;
        }

        // Estimate global noise variance from the mean of per-profile variances
        // (conservative: uses the minimum sample variance as noise floor)
        double noiseVariance = estimateNoiseVariance(profileObservations);

        // Decompose uncertainty per profile
        String[] profileNames = profileObservations.keySet().toArray(new String[0]);
        int n = profileNames.length;
        UncertaintyDecomposition[] decompositions = new UncertaintyDecomposition[n];
        int mostInformative = 0;
        double maxReducible = -1;
        double totalReducible = 0.0;

        for (int i = 0; i < n; i++) {
            double[] obs = profileObservations.get(profileNames[i]);
            decompositions[i] = FisherInformation.decompose(obs, noiseVariance);

            totalReducible += decompositions[i].reducibleUncertainty();
            if (decompositions[i].reducibleUncertainty() > maxReducible) {
                maxReducible = decompositions[i].reducibleUncertainty();
                mostInformative = i;
            }
        }

        FisherReport fisherReport = new FisherReport(
                profileNames, decompositions, mostInformative, totalReducible);

        log.debug("Fisher analysis for {}: {} profiles, most informative='{}', " +
                        "total reducible={}", workerType, n,
                profileNames[mostInformative],
                String.format("%.4f", totalReducible));

        return new FisherUncertaintyReport(
                workerType, fisherReport, n, profileNames[mostInformative]);
    }

    /**
     * Estimates the noise variance from per-profile sample variances.
     * Uses the minimum sample variance across profiles as a conservative noise floor.
     */
    private double estimateNoiseVariance(Map<String, double[]> profileObservations) {
        double minVariance = Double.MAX_VALUE;

        for (double[] obs : profileObservations.values()) {
            if (obs.length < 2) continue;
            double mean = 0.0;
            for (double v : obs) mean += v;
            mean /= obs.length;

            double variance = 0.0;
            for (double v : obs) {
                double diff = v - mean;
                variance += diff * diff;
            }
            variance /= (obs.length - 1);
            minVariance = Math.min(minVariance, variance);
        }

        return minVariance == Double.MAX_VALUE ? 0.01 : minVariance;
    }

    /**
     * Fisher uncertainty report for a worker type.
     *
     * @param workerType              the worker type analyzed
     * @param fisherReport            Fisher information decompositions
     * @param profilesAnalyzed        number of profiles with sufficient data
     * @param mostInformativeProfile  profile where exploration would yield most value
     */
    public record FisherUncertaintyReport(
            String workerType,
            FisherReport fisherReport,
            int profilesAnalyzed,
            String mostInformativeProfile
    ) {}
}
