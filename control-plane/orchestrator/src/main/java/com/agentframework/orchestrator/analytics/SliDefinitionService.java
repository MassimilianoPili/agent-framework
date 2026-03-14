package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes Service Level Indicators (SLIs) for worker types.
 *
 * <p>SLIs are objective, quantitative measurements of service behaviour.
 * This service computes 4 core SLIs per worker type, inspired by Google SRE
 * (Beyer et al., 2016):</p>
 *
 * <ul>
 *   <li><b>Availability</b>: fraction of tasks that completed successfully (DONE / total terminal)</li>
 *   <li><b>Latency P50/P95/P99</b>: task completion time distribution (dispatchedAt → completedAt)</li>
 *   <li><b>Throughput</b>: tasks completed per hour</li>
 *   <li><b>Quality</b>: fraction of tasks with reward above quality threshold</li>
 * </ul>
 *
 * @see SloTracker
 * @see ErrorBudgetCalculator
 * @see <a href="https://sre.google/sre-book/service-level-objectives/">
 *     Google SRE Book — Service Level Objectives</a>
 */
@Service
@ConditionalOnProperty(prefix = "observability-sli", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SliDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(SliDefinitionService.class);

    private final PlanItemRepository planItemRepository;
    private final TaskOutcomeRepository taskOutcomeRepository;

    @Value("${observability-sli.slo-targets.quality-threshold:0.5}")
    private double qualityThreshold;

    public SliDefinitionService(PlanItemRepository planItemRepository,
                                 TaskOutcomeRepository taskOutcomeRepository) {
        this.planItemRepository = planItemRepository;
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Computes SLIs for a specific worker type.
     *
     * @param workerType worker type name (e.g. "BE")
     * @return SLI report with all 4 indicators
     */
    public SliReport computeSlis(String workerType) {
        // Load completed plan items for this worker type
        List<PlanItem> doneItems = planItemRepository.findByStatusWithPlan(ItemStatus.DONE).stream()
                .filter(item -> item.getWorkerType().name().equals(workerType))
                .toList();
        List<PlanItem> failedItems = planItemRepository.findByStatusWithPlan(ItemStatus.FAILED).stream()
                .filter(item -> item.getWorkerType().name().equals(workerType))
                .toList();

        // Load reward data
        List<Object[]> rewardRows = taskOutcomeRepository.findRewardTimeseriesByWorkerType(workerType, 10000);

        return buildReport(workerType, doneItems, failedItems, rewardRows);
    }

    /**
     * Builds the SLI report from raw data.
     */
    SliReport buildReport(String workerType, List<PlanItem> doneItems,
                           List<PlanItem> failedItems, List<Object[]> rewardRows) {
        List<SliValue> slis = new ArrayList<>();

        // 1. Availability: DONE / (DONE + FAILED)
        int total = doneItems.size() + failedItems.size();
        double availability = total == 0 ? 1.0 : (double) doneItems.size() / total;
        slis.add(new SliValue("availability", availability, "ratio", "DONE / (DONE + FAILED)"));

        // 2. Latency percentiles (from dispatchedAt → completedAt)
        List<Long> latenciesMs = doneItems.stream()
                .filter(item -> item.getDispatchedAt() != null && item.getCompletedAt() != null)
                .map(item -> Duration.between(item.getDispatchedAt(), item.getCompletedAt()).toMillis())
                .sorted()
                .toList();

        if (!latenciesMs.isEmpty()) {
            slis.add(new SliValue("latency_p50_seconds", percentile(latenciesMs, 0.50) / 1000.0, "seconds", "50th percentile completion time"));
            slis.add(new SliValue("latency_p95_seconds", percentile(latenciesMs, 0.95) / 1000.0, "seconds", "95th percentile completion time"));
            slis.add(new SliValue("latency_p99_seconds", percentile(latenciesMs, 0.99) / 1000.0, "seconds", "99th percentile completion time"));
        }

        // 3. Throughput: tasks completed per hour (using last 24h of DONE items)
        long recentDone = doneItems.stream()
                .filter(item -> item.getCompletedAt() != null)
                .count();
        slis.add(new SliValue("throughput", recentDone, "tasks_total", "total completed tasks"));

        // 4. Quality: fraction of rewards above threshold
        List<Double> rewards = rewardRows.stream()
                .map(row -> ((Number) row[1]).doubleValue())
                .toList();
        double quality = rewards.isEmpty() ? 1.0 :
                (double) rewards.stream().filter(r -> r >= qualityThreshold).count() / rewards.size();
        slis.add(new SliValue("quality", quality, "ratio", "fraction of rewards >= " + qualityThreshold));

        log.debug("SLIs for {}: availability={:.4f}, quality={:.4f}, latency_count={}, throughput={}",
                  workerType, availability, quality, latenciesMs.size(), recentDone);

        return new SliReport(workerType, slis, total);
    }

    /**
     * Computes the p-th percentile of a sorted list.
     */
    static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /**
     * A single SLI measurement.
     *
     * @param name        SLI name (e.g. "availability", "latency_p99_seconds")
     * @param value       the measured value
     * @param unit        unit of measurement (e.g. "ratio", "seconds", "tasks/hour")
     * @param description human-readable description
     */
    public record SliValue(
            String name,
            double value,
            String unit,
            String description
    ) {}

    /**
     * SLI report for a worker type.
     *
     * @param workerType     the analysed worker type
     * @param indicators     list of computed SLIs
     * @param totalItems     total terminal items (DONE + FAILED) used for computation
     */
    public record SliReport(
            String workerType,
            List<SliValue> indicators,
            int totalItems
    ) {
        /**
         * Finds an SLI by name, or null if not present.
         */
        public SliValue findByName(String name) {
            return indicators.stream()
                    .filter(sli -> sli.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }
    }
}
