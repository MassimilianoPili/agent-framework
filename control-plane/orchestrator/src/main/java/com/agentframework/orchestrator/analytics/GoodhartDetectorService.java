package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.GoodhartDetector.*;
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
 * Audits metric health for worker profiles using Goodhart's Law detection.
 *
 * <p>Loads historical task outcomes for each profile of a worker type, checks
 * for regressional, extremal, and causal Goodhart mechanisms, and produces
 * a system-level health report with remediation recommendations.</p>
 *
 * <p>This is an analytics-only service — it does NOT modify the dispatch
 * hot path. The audit report is exposed via REST endpoint.</p>
 *
 * @see GoodhartDetector
 * @see <a href="https://arxiv.org/abs/1803.04585">
 *     Garrabrant et al. (2017), Categorizing Variants of Goodhart's Law</a>
 */
@Service
@ConditionalOnProperty(prefix = "goodhart", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoodhartDetectorService {

    private static final Logger log = LoggerFactory.getLogger(GoodhartDetectorService.class);

    static final int MAX_OUTCOMES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${goodhart.window-size:50}")
    private int windowSize;

    @Value("${goodhart.divergence-threshold:0.3}")
    private double divergenceThreshold;

    @Value("${goodhart.min-sample-size:10}")
    private int minSampleSize;

    public GoodhartDetectorService(TaskOutcomeRepository taskOutcomeRepository,
                                    WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Audits metric health for all profiles of a given worker type.
     *
     * <ol>
     *   <li>Enumerate all profiles for the worker type via registry</li>
     *   <li>For each profile, load recent outcomes (window-size)</li>
     *   <li>Check regressional (sample size), extremal (z-score),
     *       and causal (proxy-goal correlation) Goodhart mechanisms</li>
     *   <li>Compute per-profile health scores and system-level aggregate</li>
     *   <li>Generate recommendations for at-risk profiles</li>
     * </ol>
     *
     * @param workerType worker type name (e.g. "BE", "FE")
     * @return audit report, or null if no profiles have data
     */
    public GoodhartAuditReport auditMetrics(String workerType) {
        List<String> candidates = profileRegistry.profilesForWorkerType(
                WorkerType.valueOf(workerType));

        // Collect proxy (gp_mu) and goal (actual_reward) per profile
        Map<String, double[]> profileProxy = new LinkedHashMap<>();
        Map<String, double[]> profileGoal = new LinkedHashMap<>();
        Map<String, Integer> profileSizes = new LinkedHashMap<>();

        // Population-level stats for extremal detection
        List<Double> allRewards = new ArrayList<>();

        for (String profile : candidates) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, windowSize);

            if (data.isEmpty()) continue;

            // Extract gp_mu (index 8) and actual_reward (index 10)
            List<Double> proxies = new ArrayList<>();
            List<Double> goals = new ArrayList<>();
            for (Object[] row : data) {
                Double gpMu = row[8] != null ? ((Number) row[8]).doubleValue() : null;
                Double reward = row[10] != null ? ((Number) row[10]).doubleValue() : null;
                if (gpMu != null && reward != null) {
                    proxies.add(gpMu);
                    goals.add(reward);
                    allRewards.add(reward);
                }
            }

            if (!proxies.isEmpty()) {
                profileProxy.put(profile, proxies.stream().mapToDouble(Double::doubleValue).toArray());
                profileGoal.put(profile, goals.stream().mapToDouble(Double::doubleValue).toArray());
                profileSizes.put(profile, proxies.size());
            }
        }

        if (profileProxy.isEmpty()) {
            log.debug("Goodhart audit for {}: no profiles with data", workerType);
            return null;
        }

        // Compute population mean and std for extremal detection
        double popMean = allRewards.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
        double popVariance = 0.0;
        for (double r : allRewards) {
            double diff = r - popMean;
            popVariance += diff * diff;
        }
        popVariance = allRewards.size() > 1 ? popVariance / (allRewards.size() - 1) : 0.0;
        double popStd = Math.sqrt(popVariance);

        // Audit each profile
        String[] profileNames = profileProxy.keySet().toArray(new String[0]);
        int n = profileNames.length;
        MetricHealth[] healths = new MetricHealth[n];
        int profilesAtRisk = 0;
        double sumHealth = 0.0;
        List<String> recommendations = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String profile = profileNames[i];
            int sampleSize = profileSizes.get(profile);
            double[] proxy = profileProxy.get(profile);
            double[] goal = profileGoal.get(profile);

            // 1. Regressional check
            boolean regressional = GoodhartDetector.detectRegressional(sampleSize, minSampleSize);

            // 2. Extremal check (mean reward of this profile vs population)
            double profileMean = 0;
            for (double g : goal) profileMean += g;
            profileMean /= goal.length;
            boolean extremal = GoodhartDetector.detectExtremal(profileMean, popMean, popStd, 3.0);

            // 3. Causal check (proxy-goal correlation)
            double correlation = GoodhartDetector.pearsonCorrelation(proxy, goal);

            // Compute health score
            double healthScore = GoodhartDetector.metricHealthScore(regressional, extremal, correlation);

            // Determine dominant risk
            GoodhartType dominantRisk = GoodhartType.NONE;
            if (regressional) dominantRisk = GoodhartType.REGRESSIONAL;
            if (correlation < divergenceThreshold) dominantRisk = GoodhartType.CAUSAL;
            if (extremal) dominantRisk = GoodhartType.EXTREMAL;

            healths[i] = new MetricHealth(profile, healthScore, regressional, extremal,
                    correlation, dominantRisk);

            sumHealth += healthScore;
            if (healthScore < 0.5) {
                profilesAtRisk++;
                if (regressional) {
                    recommendations.add("Profile '" + profile + "': collect more data (n=" +
                            sampleSize + " < " + minSampleSize + ")");
                }
                if (correlation < divergenceThreshold) {
                    recommendations.add("Profile '" + profile + "': proxy-goal divergence " +
                            "(r=" + String.format("%.3f", correlation) + "), retrain GP model");
                }
                if (extremal) {
                    recommendations.add("Profile '" + profile + "': extremal outlier, " +
                            "investigate unusual performance pattern");
                }
            }
        }

        double systemHealthScore = n > 0 ? sumHealth / n : 1.0;

        GoodhartReport report = new GoodhartReport(
                healths, systemHealthScore, profilesAtRisk,
                recommendations.toArray(new String[0]));

        log.debug("Goodhart audit for {}: {} profiles, system health={}, at risk={}",
                workerType, n, String.format("%.2f", systemHealthScore), profilesAtRisk);

        return new GoodhartAuditReport(workerType, report, n, systemHealthScore >= 0.5);
    }

    /**
     * Goodhart audit report for a worker type.
     *
     * @param workerType       the worker type analyzed
     * @param goodhartReport   detailed Goodhart analysis
     * @param profilesAudited  number of profiles with data
     * @param systemHealthy    true if system health score &ge; 0.5
     */
    public record GoodhartAuditReport(
            String workerType,
            GoodhartReport goodhartReport,
            int profilesAudited,
            boolean systemHealthy
    ) {}
}
