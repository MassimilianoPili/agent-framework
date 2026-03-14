package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Monitors convergence of GP posteriors across worker profiles using sliding-window variance.
 *
 * <p>In the logical induction framework, convergence means the inductor's credences
 * have stabilised — the posterior is no longer changing significantly with new evidence.
 * This monitor tracks the variance of the last {@code window} reward observations per
 * profile. When variance drops below the {@code threshold}, the profile is considered
 * converged — meaning the GP model is confident about its performance prediction.</p>
 *
 * <p>Convergence status is useful for:
 * <ul>
 *   <li>Deciding when to stop exploring a profile (exploitation phase)</li>
 *   <li>Identifying stale profiles that haven't been tested recently</li>
 *   <li>Adjusting the PosteriorFloorGuard — converged profiles may tolerate tighter bounds</li>
 * </ul></p>
 *
 * @see PosteriorFloorGuard
 * @see <a href="https://arxiv.org/abs/1609.03543">
 *     Garrabrant et al. (2016), Logical Induction</a>
 */
@Service
@ConditionalOnProperty(prefix = "logical-induction", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ConvergenceMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConvergenceMonitor.class);

    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${logical-induction.convergence-window:20}")
    private int convergenceWindow;

    @Value("${logical-induction.convergence-threshold:0.005}")
    private double convergenceThreshold;

    public ConvergenceMonitor(TaskOutcomeRepository taskOutcomeRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Checks convergence for all profiles of a given worker type.
     *
     * @param workerType worker type name (e.g. "BE")
     * @return convergence report with per-profile status
     */
    public ConvergenceReport checkConvergence(String workerType) {
        List<Object[]> rows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(
                workerType, convergenceWindow * 10);

        // Group rewards by the worker_type column (all same type, but we get individual rewards)
        List<Double> rewards = new ArrayList<>();
        for (Object[] row : rows) {
            Number reward = (Number) row[1];
            if (reward != null) {
                rewards.add(reward.doubleValue());
            }
        }

        // Build per-profile convergence from the overall worker type timeseries
        // Since findRewardTimeseriesByWorkerType doesn't distinguish profiles,
        // we compute a single convergence metric for the worker type
        ProfileConvergence profileConvergence = computeProfileConvergence(
                workerType, rewards);

        List<ProfileConvergence> profiles = List.of(profileConvergence);

        long convergedCount = profiles.stream().filter(ProfileConvergence::converged).count();
        boolean allConverged = !profiles.isEmpty() && convergedCount == profiles.size();

        log.debug("Convergence check for {}: {} profile(s), {} converged (all={})",
                  workerType, profiles.size(), convergedCount, allConverged);

        return new ConvergenceReport(workerType, profiles, allConverged, convergenceWindow, convergenceThreshold);
    }

    /**
     * Computes convergence for a single profile based on its reward history.
     */
    private ProfileConvergence computeProfileConvergence(String profile, List<Double> rewards) {
        int n = rewards.size();

        if (n < 2) {
            return new ProfileConvergence(profile, n, Double.NaN, false, "insufficient data");
        }

        // Use the last 'convergenceWindow' observations
        List<Double> window = n <= convergenceWindow
                ? rewards
                : rewards.subList(n - convergenceWindow, n);

        double variance = computeVariance(window);
        boolean converged = variance < convergenceThreshold;

        String status = converged ? "converged" : (n < convergenceWindow ? "warming up" : "oscillating");

        return new ProfileConvergence(profile, n, variance, converged, status);
    }

    /**
     * Computes sample variance of a list of values.
     */
    double computeVariance(List<Double> values) {
        if (values.size() < 2) return 0.0;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumSquaredDiff = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .sum();

        return sumSquaredDiff / (values.size() - 1);  // Bessel's correction
    }

    /**
     * Full convergence report for a worker type.
     *
     * @param workerType           the analysed worker type
     * @param profiles             per-profile convergence details
     * @param allConverged         true if all profiles have converged
     * @param windowSize           sliding window size used
     * @param varianceThreshold    threshold below which a profile is considered converged
     */
    public record ConvergenceReport(
            String workerType,
            List<ProfileConvergence> profiles,
            boolean allConverged,
            int windowSize,
            double varianceThreshold
    ) {}

    /**
     * Convergence status for a single worker profile.
     *
     * @param profile      profile name
     * @param observations total number of reward observations
     * @param variance     sliding-window variance (NaN if insufficient data)
     * @param converged    true if variance < threshold
     * @param status       human-readable status: "converged", "warming up", "oscillating", "insufficient data"
     */
    public record ProfileConvergence(
            String profile,
            int observations,
            double variance,
            boolean converged,
            String status
    ) {}
}
