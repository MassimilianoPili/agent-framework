package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.eventsourcing.PlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventRepository;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Captures a consistent distributed snapshot of in-flight plan tasks using the
 * Chandy-Lamport algorithm (Chandy &amp; Lamport, 1985).
 *
 * <p>The Chandy-Lamport algorithm records a globally consistent state of a distributed system
 * without requiring processes to pause. It captures:</p>
 * <ul>
 *   <li><b>Local state</b>: the state of each process at the time the marker is received</li>
 *   <li><b>Channel state</b>: messages in-transit on each communication channel</li>
 * </ul>
 *
 * <p>Mapping to the Agent Framework:</p>
 * <ul>
 *   <li><b>Local state</b>: task outcomes already recorded in {@code task_outcomes}
 *       (completed or failed items)</li>
 *   <li><b>Channel state</b>: items with a {@code TASK_DISPATCHED} event but no corresponding
 *       entry in {@code task_outcomes} — tasks currently in transit on Redis Streams</li>
 *   <li><b>Consistent cut</b>: every terminated item has a prior dispatch event</li>
 *   <li><b>Orphaned items</b>: terminated without a dispatch event — indicates inconsistency</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "chandy-lamport", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChandyLamportSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(ChandyLamportSnapshotter.class);

    private static final String TASK_DISPATCHED = "TASK_DISPATCHED";
    private static final String TASK_COMPLETED  = "TASK_COMPLETED";
    private static final String TASK_FAILED     = "TASK_FAILED";

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final PlanEventRepository   planEventRepository;

    public ChandyLamportSnapshotter(TaskOutcomeRepository taskOutcomeRepository,
                                    PlanEventRepository planEventRepository) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.planEventRepository   = planEventRepository;
    }

    /**
     * Takes a Chandy-Lamport snapshot for the given plan.
     *
     * @param planId the plan to snapshot
     * @return snapshot report
     * @throws NullPointerException if planId is null
     */
    public SnapshotReport snapshot(UUID planId) {
        Objects.requireNonNull(planId, "planId must not be null");

        UUID    snapshotId  = UUID.randomUUID();
        Instant capturedAt  = Instant.now();

        // ── Local state: completed tasks in task_outcomes ──────────────────
        List<Object[]> outcomes = taskOutcomeRepository.findOutcomesByPlanId(planId);
        Set<String> completedTaskKeys = new LinkedHashSet<>();
        for (Object[] row : outcomes) {
            String taskKey = (String) row[3];   // col[3] = task_key
            if (taskKey != null) completedTaskKeys.add(taskKey);
        }

        // ── Channel state: scan event log for in-flight items ──────────────
        List<PlanEvent> events = planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId);

        Set<String> dispatchedIds   = new LinkedHashSet<>();  // itemId of dispatched items
        Set<String> terminatedIds   = new LinkedHashSet<>();  // itemId of completed/failed items

        for (PlanEvent e : events) {
            if (e.getItemId() == null) continue;
            String itemId = e.getItemId().toString();
            String type   = e.getEventType();
            if (TASK_DISPATCHED.equals(type))                             dispatchedIds.add(itemId);
            if (TASK_COMPLETED.equals(type) || TASK_FAILED.equals(type)) terminatedIds.add(itemId);
        }

        // In-flight = dispatched but not yet terminated (channel state)
        Set<String> inFlightItemIds = new LinkedHashSet<>(dispatchedIds);
        inFlightItemIds.removeAll(terminatedIds);

        // Orphaned = terminated without a prior dispatch (inconsistency marker)
        Set<String> orphanedItemIds = new LinkedHashSet<>(terminatedIds);
        orphanedItemIds.removeAll(dispatchedIds);

        // A consistent cut has no orphaned items
        boolean isConsistent = orphanedItemIds.isEmpty();

        log.debug("ChandyLamport snapshot={} plan={} completed={} inFlight={} orphaned={} consistent={}",
                snapshotId, planId, completedTaskKeys.size(),
                inFlightItemIds.size(), orphanedItemIds.size(), isConsistent);

        return new SnapshotReport(
                snapshotId, planId, capturedAt,
                new ArrayList<>(completedTaskKeys),
                new ArrayList<>(inFlightItemIds),
                new ArrayList<>(orphanedItemIds),
                isConsistent
        );
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Chandy-Lamport snapshot report.
     *
     * @param snapshotId       unique identifier for this snapshot
     * @param planId           the plan being snapshotted
     * @param capturedAt       wall-clock time when the snapshot was taken
     * @param completedTaskKeys task keys present in task_outcomes (local state)
     * @param inFlightItemIds  item UUIDs dispatched but not yet terminated (channel state)
     * @param orphanedItemIds  item UUIDs terminated without a prior dispatch event
     * @param isConsistent     true if no orphaned items were detected
     */
    public record SnapshotReport(
            UUID snapshotId,
            UUID planId,
            Instant capturedAt,
            List<String> completedTaskKeys,
            List<String> inFlightItemIds,
            List<String> orphanedItemIds,
            boolean isConsistent
    ) {}
}
