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
 * Auto-tunes the exploration/exploitation balance using Edge-of-Chaos (EOC) dynamics.
 *
 * <p>Complex adaptive systems are most capable — and most flexible — when operating at
 * the boundary between ordered and chaotic dynamics (Langton, 1990).  Applied to
 * worker dispatch: the system should neither lock into a single profile (over-ordered)
 * nor thrash between profiles unpredictably (over-chaotic).</p>
 *
 * <p>Lyapunov proxy:
 * <pre>
 *   lyapunov ≈ Var(successive_reward_differences) / Var(rewards)
 * </pre>
 * A lyapunov value near 0 means the reward time series is smooth — low sensitivity to
 * initial conditions, typically under-exploration.  A large positive value means high
 * sensitivity — over-exploration.  The target lyapunov (default 0) is the edge.</p>
 *
 * <p>Adaptation signal:
 * <pre>
 *   adaptation = (lyapunov − target_lyapunov) × adaptation_rate
 *   new_exploration = clip(old_exploration + adaptation, 0, 1)
 * </pre>
 * If lyapunov > target → system is too chaotic → reduce exploration.
 * If lyapunov < target → system is too ordered → increase exploration.</p>
 *
 * @see <a href="https://doi.org/10.1162/artl.1990.1.1_2.127">Langton (1990), Computation at the Edge of Chaos</a>
 */
@Service
@ConditionalOnProperty(prefix = "edge-of-chaos", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EdgeOfChaosService {

    private static final Logger log = LoggerFactory.getLogger(EdgeOfChaosService.class);

    static final int MIN_SAMPLES = 10;
    static final int MAX_SAMPLES = 200;

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${edge-of-chaos.target-lyapunov:0.0}")
    private double targetLyapunov;

    @Value("${edge-of-chaos.adaptation-rate:0.01}")
    private double adaptationRate;

    public EdgeOfChaosService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes the Lyapunov proxy and suggests a new exploration level for a worker type.
     *
     * @param workerType       worker type name (e.g. "BE")
     * @param currentExploration current exploration probability in [0, 1]
     * @return EOC tuning report, or null if insufficient data
     */
    public EOCTuningReport tune(String workerType, double currentExploration) {
        // [worker_type, actual_reward] ordered by created_at ASC
        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(
                workerType, MAX_SAMPLES);

        // Filter to correct worker type (query already filters, but be defensive)
        List<Double> rewards = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[1] != null) {
                rewards.add(((Number) row[1]).doubleValue());
            }
        }

        if (rewards.size() < MIN_SAMPLES) {
            log.debug("EdgeOfChaos for {}: only {} samples, need {}", workerType, rewards.size(), MIN_SAMPLES);
            return null;
        }

        double varReward = variance(rewards);
        if (varReward < 1e-10) {
            // Perfectly constant rewards — fully ordered system
            double adaptation = -adaptationRate;
            double newExploration = clamp(currentExploration + adaptation, 0.0, 1.0);
            return new EOCTuningReport(currentExploration, newExploration, 0.0, adaptation);
        }

        // Successive differences: captures local sensitivity
        List<Double> diffs = new ArrayList<>();
        for (int i = 1; i < rewards.size(); i++) {
            diffs.add(rewards.get(i) - rewards.get(i - 1));
        }

        double varDiffs = variance(diffs);
        double lyapunov = varDiffs / varReward;

        double adaptationSignal = (lyapunov - targetLyapunov) * adaptationRate;
        // lyapunov > target → too chaotic → adaptation < 0 → reduce exploration
        // lyapunov < target → too ordered → adaptation > 0 → increase exploration
        double newExploration = clamp(currentExploration - adaptationSignal, 0.0, 1.0);

        log.debug("EdgeOfChaos for {}: lyapunov={:.4f}, target={}, adaptation={:.4f}, " +
                  "exploration: {} → {}",
                  workerType, lyapunov, targetLyapunov, adaptationSignal,
                  String.format("%.4f", currentExploration),
                  String.format("%.4f", newExploration));

        return new EOCTuningReport(currentExploration, newExploration, lyapunov, adaptationSignal);
    }

    private static double variance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average().orElse(0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Edge-of-Chaos auto-tuning report.
     *
     * @param currentExploration exploration level before this tuning step
     * @param newExploration     exploration level after applying the EOC adaptation
     * @param lyapunovExponent   Lyapunov proxy = Var(diffs) / Var(rewards)
     * @param adaptationSignal   raw signal applied to exploration = (lyapunov − target) × rate
     */
    public record EOCTuningReport(
            double currentExploration,
            double newExploration,
            double lyapunovExponent,
            double adaptationSignal
    ) {}
}
