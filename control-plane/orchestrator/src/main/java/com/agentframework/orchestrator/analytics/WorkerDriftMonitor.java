package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Monitors worker profile reward distributions for drift using Wasserstein-1 distance.
 *
 * <p>Periodically compares each profile's recent reward distribution (last 7 days)
 * against its historical baseline (8–30 days ago). When the W₁ distance exceeds
 * a configured threshold, a {@link SpringPlanEvent#WORKER_DRIFT_DETECTED} event
 * is published for SSE listeners.</p>
 *
 * <p>Results are cached in-memory (volatile replacement for thread safety) and
 * exposed via the REST endpoint for dashboard consumption.</p>
 *
 * @see WassersteinDistance
 * @see DriftResult
 * @see <a href="https://doi.org/10.1007/978-3-540-71050-9">
 *     Villani (2009), Optimal Transport: Old and New</a>
 */
@Service
@ConditionalOnProperty(prefix = "drift", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerDriftMonitor {

    private static final Logger log = LoggerFactory.getLogger(WorkerDriftMonitor.class);

    static final double DEFAULT_THRESHOLD = 0.15;
    static final int RECENT_WINDOW_DAYS = 7;
    static final int HISTORICAL_WINDOW_DAYS = 30;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${drift.threshold:0.15}")
    private double threshold;

    private volatile List<DriftResult> latestResults = List.of();

    public WorkerDriftMonitor(TaskOutcomeRepository taskOutcomeRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Checks drift for all known worker profiles.
     *
     * <ol>
     *   <li>Query distinct profiles from task_outcomes</li>
     *   <li>For each profile, load reward timeseries</li>
     *   <li>Split into "historical" (&gt;7d ago, up to 30d) and "recent" (last 7d)</li>
     *   <li>If either window has fewer than {@link WassersteinDistance#MIN_SAMPLES}, skip</li>
     *   <li>Compute W₁(recent, historical)</li>
     *   <li>If W₁ &gt; threshold → publish {@link SpringPlanEvent#WORKER_DRIFT_DETECTED}</li>
     * </ol>
     *
     * @return list of drift results for all profiles with sufficient data
     */
    public List<DriftResult> checkAllProfiles() {
        List<String> profiles = taskOutcomeRepository.findDistinctProfiles();
        List<DriftResult> results = new ArrayList<>();
        Instant now = Instant.now();
        Instant recentCutoff = now.minus(RECENT_WINDOW_DAYS, ChronoUnit.DAYS);
        Instant historicalCutoff = now.minus(HISTORICAL_WINDOW_DAYS, ChronoUnit.DAYS);

        for (String profile : profiles) {
            List<Object[]> timeseries = taskOutcomeRepository.findRewardTimeseriesByProfile(profile);

            List<Double> recent = new ArrayList<>();
            List<Double> historical = new ArrayList<>();

            for (Object[] row : timeseries) {
                double reward = ((Number) row[0]).doubleValue();
                Instant createdAt = ((Timestamp) row[1]).toInstant();

                if (createdAt.isAfter(recentCutoff)) {
                    recent.add(reward);
                } else if (createdAt.isAfter(historicalCutoff)) {
                    historical.add(reward);
                }
            }

            if (recent.size() < WassersteinDistance.MIN_SAMPLES
                    || historical.size() < WassersteinDistance.MIN_SAMPLES) {
                continue;
            }

            double[] recentArr = recent.stream().mapToDouble(Double::doubleValue).toArray();
            double[] historicalArr = historical.stream().mapToDouble(Double::doubleValue).toArray();

            double w1 = WassersteinDistance.w1(recentArr, historicalArr);
            double recentMean = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double historicalMean = historical.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            boolean driftDetected = w1 > threshold;

            DriftResult result = new DriftResult(
                    profile, w1, recentMean, historicalMean,
                    recent.size(), historical.size(), driftDetected);
            results.add(result);

            if (driftDetected) {
                log.warn("Drift detected for profile '{}': W₁={}, recent_mean={}, historical_mean={}",
                        profile, String.format("%.4f", w1),
                        String.format("%.4f", recentMean), String.format("%.4f", historicalMean));
                eventPublisher.publishEvent(SpringPlanEvent.forSystem(SpringPlanEvent.WORKER_DRIFT_DETECTED));
            }
        }

        this.latestResults = List.copyOf(results);
        log.debug("Drift check completed: {} profiles analysed, {} with drift",
                results.size(), results.stream().filter(DriftResult::driftDetected).count());
        return results;
    }

    /** Returns the latest cached drift results (for REST endpoint). */
    public List<DriftResult> getLatestResults() {
        return latestResults;
    }

    /**
     * Penalty factor for a specific profile.
     *
     * <p>Returns 0.0 if no drift detected, or min(W₁, 0.5) if drift is active.
     * Used by {@link com.agentframework.orchestrator.gp.GpWorkerSelectionService}
     * to penalize profiles with unstable reward distributions.</p>
     *
     * @param profile the worker profile to check
     * @return penalty value in [0.0, 0.5]
     */
    public double penaltyFor(String profile) {
        for (DriftResult r : latestResults) {
            if (r.profile().equals(profile) && r.driftDetected()) {
                return Math.min(r.w1Distance(), 0.5);
            }
        }
        return 0.0;
    }

    @Scheduled(fixedDelayString = "${drift.check-interval-ms:3600000}")
    void scheduledCheck() {
        checkAllProfiles();
    }
}
