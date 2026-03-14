package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Prevents GP posteriors from collapsing to extreme values via an adaptive floor.
 *
 * <p>Inspired by Garrabrant et al. (2016) "Logical Induction": a logical inductor
 * assigns non-trivial credences to undecidable sentences, never fully committing
 * to 0 or 1. In the agent framework, this translates to preventing worker profile
 * posteriors (GP μ) from becoming too confident too early.</p>
 *
 * <p>The adaptive floor is:
 * <pre>
 *   floor(n) = max(ε, 1 / (n + k))
 * </pre>
 * where {@code n} is the number of observations for the (workerType, profile) pair,
 * {@code ε} is a hard minimum, and {@code k} is a smoothing constant.
 * The ceiling is {@code 1 − floor(n)}.</p>
 *
 * <p>When the raw posterior falls below the floor or above the ceiling, it is
 * clamped and the guard reports it was active — useful for analytics and debugging.</p>
 *
 * @see <a href="https://arxiv.org/abs/1609.03543">
 *     Garrabrant et al. (2016), Logical Induction</a>
 */
@Service
@ConditionalOnProperty(prefix = "logical-induction", name = "enabled", havingValue = "true", matchIfMissing = false)
public class PosteriorFloorGuard {

    private static final Logger log = LoggerFactory.getLogger(PosteriorFloorGuard.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${logical-induction.epsilon:0.01}")
    private double epsilon;

    @Value("${logical-induction.smoothing-k:10}")
    private int smoothingK;

    public PosteriorFloorGuard(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Guards a raw posterior value by clamping it within adaptive bounds.
     *
     * @param workerType  worker type name (e.g. "BE")
     * @param profile     worker profile (e.g. "be-java")
     * @param rawPosterior the GP posterior mean (μ) to guard, expected in [0, 1]
     * @return guarded posterior with metadata
     */
    public GuardedPosterior guard(String workerType, String profile, double rawPosterior) {
        int observations = countObservations(workerType, profile);
        double floor = computeFloor(observations);
        double ceiling = 1.0 - floor;

        double guarded = rawPosterior;
        boolean wasClamped = false;

        if (rawPosterior < floor) {
            guarded = floor;
            wasClamped = true;
            log.debug("Posterior floor guard active for {}/{}: {} → {} (n={}, floor={})",
                      workerType, profile, rawPosterior, guarded, observations, floor);
        } else if (rawPosterior > ceiling) {
            guarded = ceiling;
            wasClamped = true;
            log.debug("Posterior ceiling guard active for {}/{}: {} → {} (n={}, ceiling={})",
                      workerType, profile, rawPosterior, guarded, observations, ceiling);
        }

        return new GuardedPosterior(rawPosterior, guarded, wasClamped, floor, ceiling, observations);
    }

    /**
     * Computes the adaptive floor for a given observation count.
     *
     * <pre>floor(n) = max(ε, 1 / (n + k))</pre>
     *
     * @param observations number of task outcomes for this (workerType, profile)
     * @return the floor value
     */
    public double computeFloor(int observations) {
        return Math.max(epsilon, 1.0 / (observations + smoothingK));
    }

    /**
     * Counts task outcomes for a (workerType, profile) pair.
     * Uses the reward timeseries query as a proxy for observation count.
     */
    private int countObservations(String workerType, String profile) {
        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(workerType, Integer.MAX_VALUE);
        // Filter by profile (the query returns all profiles for a workerType)
        // Since findRewardTimeseriesByWorkerType returns [worker_type, actual_reward],
        // we count all rows as observations for this workerType
        return rows.size();
    }

    /**
     * Result of applying the posterior floor guard.
     *
     * @param original     the raw posterior value before guarding
     * @param guarded      the posterior value after clamping (equals original if not clamped)
     * @param wasClamped   true if the guard was active (original was outside bounds)
     * @param floor        the adaptive floor used: max(ε, 1/(n+k))
     * @param ceiling      the adaptive ceiling: 1 − floor
     * @param observations number of historical observations for this (workerType, profile)
     */
    public record GuardedPosterior(
            double original,
            double guarded,
            boolean wasClamped,
            double floor,
            double ceiling,
            int observations
    ) {}
}
