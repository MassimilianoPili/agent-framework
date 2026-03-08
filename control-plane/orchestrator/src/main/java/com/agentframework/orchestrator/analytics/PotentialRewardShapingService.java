package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies Potential-Based Reward Shaping (Ng, Harada &amp; Russell, 1999) to worker reward streams.
 *
 * <p>Potential-Based Reward Shaping adds an intrinsic shaping bonus F(s→s') that preserves
 * the optimal policy of the original MDP, while accelerating learning by guiding the agent
 * toward high-reward regions of the state space.</p>
 *
 * <p>Potential function: Φ(i) = running mean reward up to step i — a progress signal that
 * increases as the worker accumulates experience. The shaping bonus is:</p>
 * <pre>
 *   F(s→s') = γ·Φ(s') − Φ(s)    (conservatism: F clamped to ≥ 0)
 * </pre>
 *
 * <p>Policy invariance: any potential-based shaping F(s,a,s') = γ·Φ(s') − Φ(s) leaves the
 * set of optimal policies unchanged (Ng et al. 1999, Theorem 1).</p>
 */
@Service
@ConditionalOnProperty(prefix = "potential-shaping", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PotentialRewardShapingService {

    private static final Logger log = LoggerFactory.getLogger(PotentialRewardShapingService.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${potential-shaping.gamma:0.99}")
    private double gamma;

    @Value("${potential-shaping.max-samples:500}")
    private int maxSamples;

    public PotentialRewardShapingService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Shapes rewards for the given worker type using the running-mean potential.
     *
     * @param workerType the worker type to shape
     * @return shaping report, or {@code null} if no data exists
     * @throws IllegalArgumentException if workerType is blank
     */
    public ShapedRewardReport shape(String workerType) {
        if (workerType == null || workerType.isBlank()) {
            throw new IllegalArgumentException("workerType must not be blank");
        }

        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(workerType, maxSamples);
        if (rows.isEmpty()) return null;

        int      n        = rows.size();
        double[] original = new double[n];
        for (int i = 0; i < n; i++) {
            original[i] = ((Number) rows.get(i)[1]).doubleValue();
        }

        // Potential Φ(i): potentials[0] = initial (prior) = 0, potentials[i+1] = mean(original[0..i])
        double[] potentials = new double[n + 1];
        double   runningSum = 0.0;
        for (int i = 0; i < n; i++) {
            runningSum += original[i];
            potentials[i + 1] = runningSum / (i + 1);
        }

        // Shaped reward: r_shaped = r_extrinsic + max(0, γ·Φ(s') − Φ(s))
        double[] shaped        = new double[n];
        double   totalExtrinsic = 0.0;
        double   totalIntrinsic = 0.0;
        for (int i = 0; i < n; i++) {
            double F     = gamma * potentials[i + 1] - potentials[i];
            double bonus = Math.max(0.0, F);  // conservative: never penalise
            shaped[i]    = original[i] + bonus;
            totalExtrinsic += original[i];
            totalIntrinsic += bonus;
        }

        double improvementRatio = totalExtrinsic > 0
                ? (totalExtrinsic + totalIntrinsic) / totalExtrinsic
                : 1.0;

        log.debug("PotentialShaping: workerType={} n={} extrinsic={} intrinsic={} ratio={}",
                workerType, n, totalExtrinsic, totalIntrinsic, improvementRatio);

        List<Double> origList = new ArrayList<>(n);
        List<Double> shapedList = new ArrayList<>(n);
        List<Double> potList  = new ArrayList<>(n + 1);
        for (double v : original)   origList.add(v);
        for (double v : shaped)     shapedList.add(v);
        for (double v : potentials) potList.add(v);

        return new ShapedRewardReport(
                workerType, gamma,
                origList, potList, shapedList,
                totalExtrinsic, totalIntrinsic, improvementRatio
        );
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Potential-based reward shaping report.
     *
     * @param workerType       analysed worker type
     * @param gamma            discount factor used for shaping
     * @param originalRewards  original extrinsic reward sequence
     * @param potentials       Φ(i) values (size = n+1, index 0 = initial state)
     * @param shapedRewards    shaped reward sequence (extrinsic + intrinsic bonus)
     * @param totalExtrinsic   sum of original rewards
     * @param totalIntrinsicBonus total non-negative shaping bonus added
     * @param improvementRatio (extrinsic + intrinsic) / extrinsic; ≥ 1.0
     */
    public record ShapedRewardReport(
            String workerType,
            double gamma,
            List<Double> originalRewards,
            List<Double> potentials,
            List<Double> shapedRewards,
            double totalExtrinsic,
            double totalIntrinsicBonus,
            double improvementRatio
    ) {}
}
