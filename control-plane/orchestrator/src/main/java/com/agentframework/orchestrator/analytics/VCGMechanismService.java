package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.VCGMechanism.VCGResult;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies VCG mechanism design to compute truthful pricing for worker profiles.
 *
 * <p>Loads historical task outcomes for each profile of a worker type, computes
 * the mean actual reward as the profile's "bid" (valuation), then runs a
 * single-item Vickrey auction to determine the optimal allocation and
 * second-price payment.</p>
 *
 * <p>This is an analytics-only service — it does NOT modify the dispatch
 * hot path. The pricing report is exposed via REST endpoint for dashboard
 * consumption and future integration.</p>
 *
 * @see VCGMechanism
 * @see <a href="https://doi.org/10.2307/2977633">
 *     Vickrey (1961), Counterspeculation, Auctions, and Competitive Sealed Tenders</a>
 */
@Service
@ConditionalOnProperty(prefix = "vcg", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VCGMechanismService {

    private static final Logger log = LoggerFactory.getLogger(VCGMechanismService.class);

    static final int MIN_OUTCOMES = 5;
    static final int MAX_OUTCOMES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    public VCGMechanismService(TaskOutcomeRepository taskOutcomeRepository,
                                WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Computes VCG pricing for candidate worker profiles of a given worker type.
     *
     * <ol>
     *   <li>Enumerate all profiles for the worker type via registry</li>
     *   <li>For each profile, load recent outcomes and compute mean reward as "bid"</li>
     *   <li>Filter profiles with insufficient data (&lt; {@link #MIN_OUTCOMES})</li>
     *   <li>If fewer than 2 profiles have data, return report with null result</li>
     *   <li>Run VCG single-item auction on the bids</li>
     * </ol>
     *
     * @param workerType worker type name (e.g. "BE", "FE")
     * @return pricing report with VCG result, or null result if insufficient competition
     */
    public VCGPricingReport computePricing(String workerType) {
        List<String> candidates = profileRegistry.profilesForWorkerType(
                WorkerType.valueOf(workerType));

        Map<String, Double> profileBids = new LinkedHashMap<>();

        for (String profile : candidates) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_OUTCOMES);

            if (data.size() < MIN_OUTCOMES) {
                continue;
            }

            // Compute mean actual_reward as the profile's bid
            double sum = 0.0;
            int count = 0;
            for (Object[] row : data) {
                // findTrainingDataRaw index 10 = actual_reward
                Double reward = row[10] != null ? ((Number) row[10]).doubleValue() : null;
                if (reward != null) {
                    sum += reward;
                    count++;
                }
            }

            if (count >= MIN_OUTCOMES) {
                profileBids.put(profile, sum / count);
            }
        }

        if (profileBids.size() < 2) {
            log.debug("VCG pricing for {}: insufficient competition ({} profiles with data)",
                    workerType, profileBids.size());

            // Single profile or none: no meaningful VCG result
            VCGResult singleResult = null;
            if (profileBids.size() == 1) {
                var entry = profileBids.entrySet().iterator().next();
                singleResult = new VCGResult(entry.getKey(), 0, entry.getValue(),
                        0.0, entry.getValue(), entry.getValue());
            }
            return new VCGPricingReport(workerType, singleResult, profileBids, profileBids.size());
        }

        // Run VCG auction
        String[] names = profileBids.keySet().toArray(new String[0]);
        double[] bids = profileBids.values().stream().mapToDouble(Double::doubleValue).toArray();

        VCGResult result = VCGMechanism.compute(names, bids);

        log.debug("VCG pricing for {}: winner='{}' (bid={}, payment={}, rent={}), {} profiles",
                workerType, result.winner(),
                String.format("%.4f", result.winnerBid()),
                String.format("%.4f", result.payment()),
                String.format("%.4f", result.informationRent()),
                profileBids.size());

        return new VCGPricingReport(workerType, result, profileBids, profileBids.size());
    }

    /**
     * VCG pricing report for a worker type.
     *
     * @param workerType        the worker type analyzed
     * @param result            VCG auction result (null if &lt; 2 profiles with data)
     * @param profileBids       map of profile → mean reward bid
     * @param profilesEvaluated number of profiles with sufficient data
     */
    public record VCGPricingReport(
            String workerType,
            VCGResult result,
            Map<String, Double> profileBids,
            int profilesEvaluated
    ) {}
}
