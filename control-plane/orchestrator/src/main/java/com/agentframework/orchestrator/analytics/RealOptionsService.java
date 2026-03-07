package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.RealOptions.DeferralDecision;
import com.agentframework.orchestrator.analytics.RealOptions.RealOptionsReport;
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
 * Evaluates task deferral opportunities using Real Options Theory.
 *
 * <p>Loads historical task outcomes for each profile of a worker type,
 * estimates volatility and expected rewards, then applies the perpetual
 * American option framework to recommend deferral or execution.</p>
 *
 * <p>This is an analytics-only service — it does NOT modify the dispatch
 * hot path. The valuation report is exposed via REST endpoint.</p>
 *
 * @see RealOptions
 * @see <a href="https://press.princeton.edu/books/hardcover/9780691034102/investment-under-uncertainty">
 *     Dixit &amp; Pindyck (1994), Investment under Uncertainty</a>
 */
@Service
@ConditionalOnProperty(prefix = "real-options", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RealOptionsService {

    private static final Logger log = LoggerFactory.getLogger(RealOptionsService.class);

    static final int MAX_OUTCOMES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${real-options.discount-rate:0.05}")
    private double discountRate;

    @Value("${real-options.urgency-weight:0.1}")
    private double urgencyWeight;

    @Value("${real-options.min-observations:5}")
    private int minObservations;

    public RealOptionsService(TaskOutcomeRepository taskOutcomeRepository,
                               WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Evaluates deferral for all profiles of a given worker type.
     *
     * <ol>
     *   <li>Enumerate profiles via registry</li>
     *   <li>Load recent outcomes per profile</li>
     *   <li>Estimate volatility σ (std of actual_reward)</li>
     *   <li>Compute mean reward V and token cost proxy I</li>
     *   <li>Apply Real Options deferral decision per profile</li>
     *   <li>Aggregate into report</li>
     * </ol>
     *
     * @param workerType worker type name (e.g. "BE", "FE")
     * @return valuation report, or null if no profiles have sufficient data
     */
    public RealOptionsValuationReport evaluateDeferral(String workerType) {
        List<String> candidates = profileRegistry.profilesForWorkerType(
                WorkerType.valueOf(workerType));

        Map<String, double[]> profileRewards = new LinkedHashMap<>();

        for (String profile : candidates) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_OUTCOMES);

            if (data.isEmpty()) continue;

            List<Double> rewards = new ArrayList<>();
            for (Object[] row : data) {
                Double reward = row[10] != null ? ((Number) row[10]).doubleValue() : null;
                if (reward != null) {
                    rewards.add(reward);
                }
            }

            if (rewards.size() >= minObservations) {
                profileRewards.put(profile, rewards.stream()
                        .mapToDouble(Double::doubleValue).toArray());
            }
        }

        if (profileRewards.isEmpty()) {
            log.debug("Real Options for {}: no profiles with sufficient data", workerType);
            return null;
        }

        String[] profileNames = profileRewards.keySet().toArray(new String[0]);
        int n = profileNames.length;
        DeferralDecision[] decisions = new DeferralDecision[n];
        int profilesDeferred = 0;
        double sumOptionValue = 0.0;

        for (int i = 0; i < n; i++) {
            double[] rewards = profileRewards.get(profileNames[i]);

            double meanReward = 0;
            for (double r : rewards) meanReward += r;
            meanReward /= rewards.length;

            double volatility = RealOptions.estimateVolatility(rewards);

            // Token cost proxy: 1.0 - meanReward (higher reward → lower effective cost)
            double investmentCost = Math.max(0.1, 1.0 - meanReward);

            // Base convenience yield (no urgency factor in analytics mode)
            double convenienceYield = RealOptions.adjustedConvenienceYield(
                    urgencyWeight, urgencyWeight, 0.0);

            decisions[i] = RealOptions.shouldDefer(
                    meanReward, volatility, investmentCost, discountRate, convenienceYield);

            if (decisions[i].shouldDefer()) {
                profilesDeferred++;
                sumOptionValue += decisions[i].optionValue();
            }
        }

        int profilesReady = n - profilesDeferred;
        double avgOptionValue = profilesDeferred > 0 ? sumOptionValue / profilesDeferred : 0.0;

        RealOptionsReport report = new RealOptionsReport(
                profileNames, decisions, profilesDeferred, profilesReady, avgOptionValue);

        double deferralRatio = n > 0 ? (double) profilesDeferred / n : 0.0;

        log.debug("Real Options for {}: {} profiles, {} deferred, {} ready, ratio={:.2f}",
                workerType, n, profilesDeferred, profilesReady, deferralRatio);

        return new RealOptionsValuationReport(workerType, report, n, deferralRatio);
    }

    /**
     * Real Options valuation report for a worker type.
     *
     * @param workerType          the worker type analyzed
     * @param optionsReport       detailed per-profile analysis
     * @param profilesEvaluated   number of profiles with sufficient data
     * @param systemDeferralRatio fraction of profiles recommended for deferral
     */
    public record RealOptionsValuationReport(
            String workerType,
            RealOptionsReport optionsReport,
            int profilesEvaluated,
            double systemDeferralRatio
    ) {}
}
