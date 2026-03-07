package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.orchestration.OptimalStopping.StoppingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies Optimal Stopping (Secretary Problem) to task acceptance decisions.
 *
 * <p>Loads historical rewards for a worker type, applies the 1/e rule to
 * determine a threshold, and evaluates whether a new task candidate should
 * be accepted based on its expected reward.</p>
 *
 * <p>This is exposed as an analytics endpoint. Integration into the dispatch
 * hot path can be added in future phases.</p>
 *
 * @see OptimalStopping
 * @see <a href="https://doi.org/10.1214/ss/1177012493">
 *     Ferguson (1989), Who Solved the Secretary Problem?, Statistical Science</a>
 */
@Service
@ConditionalOnProperty(prefix = "optimal-stopping", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OptimalStoppingService {

    private static final Logger log = LoggerFactory.getLogger(OptimalStoppingService.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${optimal-stopping.observation-fraction:0.3679}")
    private double observationFraction;

    public OptimalStoppingService(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Evaluates whether to accept a candidate for a given worker type.
     *
     * @param workerType      worker type name
     * @param candidateReward expected reward of the candidate
     * @return stopping decision with threshold and accept/reject
     */
    public StoppingDecision evaluateForWorkerType(String workerType, double candidateReward) {
        double[] rewards = loadHistoricalRewards(workerType);

        if (rewards.length == 0) {
            // No history → accept any candidate
            return new StoppingDecision(0.0, 0, 0, true, candidateReward);
        }

        StoppingDecision decision = OptimalStopping.evaluate(
                rewards, candidateReward, observationFraction);

        log.debug("Optimal stopping for '{}': candidate={}, threshold={}, accept={}",
                workerType, String.format("%.3f", candidateReward),
                String.format("%.3f", decision.threshold()), decision.shouldAccept());

        return decision;
    }

    /**
     * Returns the current threshold for a worker type (for display purposes).
     *
     * @param workerType worker type name
     * @return threshold value, or 0.0 if insufficient history
     */
    public double currentThreshold(String workerType) {
        double[] rewards = loadHistoricalRewards(workerType);
        if (rewards.length == 0) return 0.0;

        int obsSize = OptimalStopping.observationSize(rewards.length, observationFraction);
        double[] obsRewards = new double[Math.min(obsSize, rewards.length)];
        System.arraycopy(rewards, 0, obsRewards, 0, obsRewards.length);
        return OptimalStopping.threshold(obsRewards);
    }

    /**
     * Loads historical rewards for a worker type from task_outcomes.
     *
     * <p>Uses {@link TaskOutcomeRepository#findRewardsByWorkerType()} and filters
     * for the requested worker type.</p>
     */
    private double[] loadHistoricalRewards(String workerType) {
        List<Object[]> allRewards = taskOutcomeRepository.findRewardsByWorkerType();
        List<Double> filtered = new ArrayList<>();

        for (Object[] row : allRewards) {
            String wt = (String) row[0];
            if (wt.equals(workerType) && row[1] != null) {
                filtered.add(((Number) row[1]).doubleValue());
            }
        }

        return filtered.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
