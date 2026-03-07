package com.agentframework.orchestrator.graph;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Domain bridge: extracts durations and predecessors from a {@link Plan},
 * feeds them into {@link TropicalScheduler}, and packages results as a view DTO.
 *
 * <p>Duration extraction strategy:
 * <ul>
 *   <li>DONE items: {@code completedAt - dispatchedAt} (actual elapsed time)</li>
 *   <li>All other items: {@link #DEFAULT_ESTIMATED_MS} (5 minutes)</li>
 * </ul>
 */
@Service
public class CriticalPathCalculator {

    /** Default estimated duration for non-DONE items: 5 minutes. */
    static final double DEFAULT_ESTIMATED_MS = 300_000.0;

    /**
     * Computes the full tropical schedule for a plan.
     */
    public TropicalScheduler.ScheduleResult computeSchedule(Plan plan) {
        Map<String, Double> durations = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();

        for (PlanItem item : plan.getItems()) {
            durations.put(item.getTaskKey(), extractDurationMs(item));
            predecessors.put(item.getTaskKey(), item.getDependsOn());
        }

        return new TropicalScheduler(durations, predecessors).compute();
    }

    /**
     * Builds a complete view DTO for API exposure.
     */
    public ScheduleView buildView(Plan plan) {
        var result = computeSchedule(plan);

        List<NodeSchedule> nodes = new ArrayList<>();
        Set<String> criticalSet = new HashSet<>(result.criticalPath());

        for (PlanItem item : plan.getItems()) {
            String key = item.getTaskKey();
            nodes.add(new NodeSchedule(
                    key,
                    item.getWorkerType().name(),
                    item.getStatus().name(),
                    extractDurationMs(item),
                    result.earliestStart().getOrDefault(key, 0.0),
                    result.latestStart().getOrDefault(key, 0.0),
                    result.totalFloat().getOrDefault(key, 0.0),
                    criticalSet.contains(key)
            ));
        }

        return new ScheduleView(
                plan.getId(),
                plan.getStatus().name(),
                result.makespanMs(),
                result.criticalPath(),
                nodes
        );
    }

    /**
     * Extracts duration in milliseconds from a PlanItem.
     * DONE items use actual elapsed time; others use default estimate.
     */
    double extractDurationMs(PlanItem item) {
        if (item.getDispatchedAt() != null && item.getCompletedAt() != null) {
            long ms = Duration.between(item.getDispatchedAt(), item.getCompletedAt()).toMillis();
            return Math.max(ms, 0);
        }
        return DEFAULT_ESTIMATED_MS;
    }

    // ── View DTOs ──────────────────────────────────────────────────────────

    public record ScheduleView(
            UUID planId,
            String planStatus,
            double makespanMs,
            List<String> criticalPath,
            List<NodeSchedule> nodes
    ) {}

    public record NodeSchedule(
            String taskKey,
            String workerType,
            String status,
            double durationMs,
            double earliestStartMs,
            double latestStartMs,
            double totalFloat,
            boolean onCriticalPath
    ) {}
}
