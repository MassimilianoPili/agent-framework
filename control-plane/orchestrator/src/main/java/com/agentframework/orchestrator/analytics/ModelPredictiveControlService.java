package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ModelPredictiveControl.*;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies Model Predictive Control to optimize task scheduling over a finite horizon.
 *
 * <p>Loads historical task outcomes for each profile of a worker type, computes
 * mean reward and mean token cost per profile, then runs MPC optimization to
 * find the best dispatch sequence. Only the first action is recommended
 * (receding horizon principle).</p>
 *
 * <p>This is an analytics-only service — it does NOT modify the dispatch
 * hot path. The scheduling report is exposed via REST endpoint.</p>
 *
 * @see ModelPredictiveControl
 * @see <a href="https://doi.org/10.1007/978-3-319-24853-0">
 *     Camacho &amp; Bordons (2007), Model Predictive Control, Springer</a>
 */
@Service
@ConditionalOnProperty(prefix = "mpc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ModelPredictiveControlService {

    private static final Logger log = LoggerFactory.getLogger(ModelPredictiveControlService.class);

    static final int MIN_OUTCOMES = 5;
    static final int MAX_OUTCOMES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${mpc.horizon:3}")
    private int horizon;

    @Value("${mpc.max-candidates:10}")
    private int maxCandidates;

    public ModelPredictiveControlService(TaskOutcomeRepository taskOutcomeRepository,
                                          WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Computes optimal scheduling for candidate worker profiles of a given worker type.
     *
     * <ol>
     *   <li>Enumerate all profiles for the worker type via registry</li>
     *   <li>For each profile, load recent outcomes and compute mean reward/cost</li>
     *   <li>Filter profiles with insufficient data (&lt; {@link #MIN_OUTCOMES})</li>
     *   <li>Build a {@link PlanState} from the profile rewards and costs</li>
     *   <li>Run MPC optimization over the configured horizon</li>
     * </ol>
     *
     * @param workerType worker type name (e.g. "BE", "FE")
     * @return scheduling report with MPC result, or null if insufficient data
     */
    public MpcScheduleReport computeSchedule(String workerType) {
        List<String> candidates = profileRegistry.profilesForWorkerType(
                WorkerType.valueOf(workerType));

        Map<String, Double> profileRewards = new LinkedHashMap<>();
        Map<String, Double> profileCosts = new LinkedHashMap<>();

        for (String profile : candidates) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_OUTCOMES);

            if (data.size() < MIN_OUTCOMES) {
                continue;
            }

            // Compute mean actual_reward and mean token cost (gp_mu as proxy for cost)
            // findTrainingDataRaw columns: [8]=gp_mu, [9]=gp_sigma2, [10]=actual_reward
            double sumReward = 0.0;
            double sumCost = 0.0;
            int count = 0;

            for (Object[] row : data) {
                Double reward = row[10] != null ? ((Number) row[10]).doubleValue() : null;
                Double gpMu = row[8] != null ? ((Number) row[8]).doubleValue() : null;

                if (reward != null) {
                    sumReward += reward;
                    // Use gp_sigma2 as a proxy for uncertainty cost if available,
                    // otherwise use a baseline cost proportional to (1 - reward)
                    double cost = gpMu != null ? Math.max(0.01, 1.0 - gpMu) : 0.3;
                    sumCost += cost;
                    count++;
                }
            }

            if (count >= MIN_OUTCOMES) {
                profileRewards.put(profile, sumReward / count);
                profileCosts.put(profile, sumCost / count);
            }
        }

        if (profileRewards.isEmpty()) {
            log.debug("MPC schedule for {}: no profiles with sufficient data", workerType);
            return null;
        }

        // Build PlanState from profile data
        String[] profileNames = profileRewards.keySet().toArray(new String[0]);
        int n = profileNames.length;
        double[] rewards = new double[n];
        double[] costs = new double[n];

        for (int i = 0; i < n; i++) {
            rewards[i] = profileRewards.get(profileNames[i]);
            costs[i] = profileCosts.get(profileNames[i]);
        }

        // Budget = 1.0 (normalized — represents total allocation capacity)
        PlanState state = new PlanState(rewards, costs, 1.0, 0);
        MpcReport mpcResult = ModelPredictiveControl.optimize(state, horizon, maxCandidates);

        log.debug("MPC schedule for {}: {} profiles, horizon={}, trajectories={}, " +
                        "recommended={}",
                workerType, n, mpcResult.horizonUsed(),
                mpcResult.trajectoriesEvaluated(),
                mpcResult.recommendedAction() != null
                        ? profileNames[mpcResult.recommendedAction().taskIndex()]
                        : "none");

        return new MpcScheduleReport(workerType, mpcResult, profileRewards, n);
    }

    /**
     * MPC scheduling report for a worker type.
     *
     * @param workerType        the worker type analyzed
     * @param mpcResult         MPC optimization result
     * @param profileRewards    map of profile → mean reward
     * @param profilesEvaluated number of profiles with sufficient data
     */
    public record MpcScheduleReport(
            String workerType,
            MpcReport mpcResult,
            Map<String, Double> profileRewards,
            int profilesEvaluated
    ) {}
}
