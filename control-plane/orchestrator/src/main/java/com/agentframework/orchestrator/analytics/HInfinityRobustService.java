package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects worker profiles using H∞ (H-infinity) robust control principles.
 *
 * <p>Standard GP-based selection maximises <em>mean</em> reward, which can
 * perform poorly when reward variance is high. H∞ minimises the worst-case
 * performance: it selects the profile that performs best under the most
 * adversarial realisation of uncertainty.</p>
 *
 * <p>H∞ norm approximation:
 * <pre>
 *   worst_case_reward(profile) = mean(reward) − k · std(reward)
 *   k = Φ⁻¹(confidence_level)   [normal quantile, e.g. 1.645 for 95%]
 *   robust_choice = argmax worst_case_reward(profile)
 * </pre>
 * The profile maximising the worst-case reward is robust to high-variance failures.</p>
 *
 * @see <a href="https://doi.org/10.1007/978-1-4612-0555-8">
 *     Zhou, Doyle &amp; Glover (1996), Robust and Optimal Control</a>
 */
@Service
@ConditionalOnProperty(prefix = "h-infinity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HInfinityRobustService {

    private static final Logger log = LoggerFactory.getLogger(HInfinityRobustService.class);

    static final int MIN_SAMPLES = 5;
    static final int MAX_SAMPLES = 100;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final WorkerProfileRegistry profileRegistry;

    @Value("${h-infinity.confidence-level:0.95}")
    private double confidenceLevel;

    public HInfinityRobustService(TaskOutcomeRepository taskOutcomeRepository,
                                   WorkerProfileRegistry profileRegistry) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Computes the H∞ robust profile choice for the given worker type.
     *
     * @param workerType worker type name (e.g. "BE")
     * @return robust dispatch report, or null if insufficient data
     */
    public RobustDispatchReport computeRobustChoice(String workerType) {
        List<String> profiles = profileRegistry.profilesForWorkerType(WorkerType.valueOf(workerType));

        // Normal quantile for worst-case bound
        NormalDistribution normal = new NormalDistribution(0, 1);
        double k = normal.inverseCumulativeProbability(confidenceLevel);

        List<String> candidateNames  = new ArrayList<>();
        List<Double> hInfNormsList   = new ArrayList<>();

        for (String profile : profiles) {
            List<Object[]> data = taskOutcomeRepository.findTrainingDataRaw(
                    workerType, profile, MAX_SAMPLES);

            List<Double> rewards = new ArrayList<>();
            for (Object[] row : data) {
                if (row[10] != null) {
                    rewards.add(((Number) row[10]).doubleValue());
                }
            }

            if (rewards.size() < MIN_SAMPLES) continue;

            double mean = rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = rewards.stream()
                    .mapToDouble(r -> (r - mean) * (r - mean))
                    .average().orElse(0);
            double std = Math.sqrt(variance);

            // worst_case_reward = mean - k * std (higher is better — we want argmax)
            // In H∞ norm convention we use the "penalty" form: lower norm = better
            // H∞_norm = -(mean - k*std) so that argmin H∞_norm = argmax worst_case
            double hInfNorm = -(mean - k * std);

            candidateNames.add(profile);
            hInfNormsList.add(hInfNorm);
        }

        if (candidateNames.isEmpty()) {
            log.debug("H∞ for {}: no profiles with sufficient data", workerType);
            return null;
        }

        // Robust choice: profile with minimum H∞ norm (= maximum worst-case reward)
        int bestIdx = 0;
        for (int i = 1; i < hInfNormsList.size(); i++) {
            if (hInfNormsList.get(i) < hInfNormsList.get(bestIdx)) {
                bestIdx = i;
            }
        }

        String robustChoice    = candidateNames.get(bestIdx);
        double worstCaseReward = -hInfNormsList.get(bestIdx);

        String[] candidates = candidateNames.toArray(new String[0]);
        double[] hInfNorms  = hInfNormsList.stream().mapToDouble(Double::doubleValue).toArray();

        log.debug("H∞ robust choice for {}: '{}', worst-case reward={:.4f}, k={}",
                  workerType, robustChoice,
                  worstCaseReward, String.format("%.3f", k));

        return new RobustDispatchReport(candidates, hInfNorms, robustChoice, worstCaseReward);
    }

    /**
     * H∞ robust dispatch report.
     *
     * @param candidates      all evaluated worker profile names
     * @param hInfNorms       H∞ norms for each candidate (lower = better)
     * @param robustChoice    profile with the lowest H∞ norm (best worst-case guarantee)
     * @param worstCaseReward worst-case reward of the robust choice = mean − k·std
     */
    public record RobustDispatchReport(
            String[] candidates,
            double[] hInfNorms,
            String robustChoice,
            double worstCaseReward
    ) {}
}
