package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.eventsourcing.PlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventRepository;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
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
 *   <li><b>Local state</b>: the status of each PlanItem (WAITING, DISPATCHED, RUNNING, DONE, FAILED, etc.)</li>
 *   <li><b>Channel state</b>: messages in-transit — items DISPATCHED but not yet terminated in the event log</li>
 *   <li><b>Marker</b>: the sequence number of the last PlanEvent, serving as the logical clock position</li>
 * </ul>
 *
 * <h3>Consistent cut verification:</h3>
 * <p>A cut is consistent if it satisfies the Chandy-Lamport invariant: for every message received
 * (TASK_COMPLETED/FAILED event), the corresponding send (TASK_DISPATCHED event) is also in the cut.
 * Violations (orphaned items) indicate event log corruption or out-of-order processing.</p>
 *
 * <p>Additionally, the snapshot verifies <em>state-event coherence</em>: each PlanItem's materialized
 * status must be consistent with its event history. An item in DISPATCHED/RUNNING state without a
 * corresponding TASK_DISPATCHED event is a coherence violation.</p>
 */
@Service
@ConditionalOnProperty(prefix = "chandy-lamport", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChandyLamportSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(ChandyLamportSnapshotter.class);

    private static final String TASK_DISPATCHED = "TASK_DISPATCHED";
    private static final String TASK_COMPLETED  = "TASK_COMPLETED";
    private static final String TASK_FAILED     = "TASK_FAILED";

    private final PlanItemRepository    planItemRepository;
    private final TaskOutcomeRepository taskOutcomeRepository;
    private final PlanEventRepository   planEventRepository;

    public ChandyLamportSnapshotter(PlanItemRepository planItemRepository,
                                    TaskOutcomeRepository taskOutcomeRepository,
                                    PlanEventRepository planEventRepository) {
        this.planItemRepository    = planItemRepository;
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

        UUID    snapshotId = UUID.randomUUID();
        Instant capturedAt = Instant.now();

        // ── Local state: materialized PlanItem statuses ──────────────────
        List<PlanItem> items = planItemRepository.findByPlanId(planId);
        Map<String, ProcessState> processStates = new LinkedHashMap<>();
        for (PlanItem item : items) {
            processStates.put(item.getTaskKey(), new ProcessState(
                    item.getTaskKey(), item.getId(), item.getStatus()));
        }

        // ── Task outcomes (completed work) ───────────────────────────────
        List<Object[]> outcomes = taskOutcomeRepository.findOutcomesByPlanId(planId);
        Set<String> completedTaskKeys = new LinkedHashSet<>();
        for (Object[] row : outcomes) {
            String taskKey = (String) row[3]; // col[3] = task_key
            if (taskKey != null) completedTaskKeys.add(taskKey);
        }

        // ── Event log scan: channel state + marker position ─────────────
        List<PlanEvent> events = planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId);

        // Marker = sequence number of the last event (logical clock)
        long markerSequence = events.isEmpty() ? 0L
                : events.get(events.size() - 1).getSequenceNumber();

        Set<String> dispatchedIds = new LinkedHashSet<>();
        Set<String> terminatedIds = new LinkedHashSet<>();

        for (PlanEvent e : events) {
            if (e.getItemId() == null) continue;
            String itemId = e.getItemId().toString();
            String type = e.getEventType();
            if (TASK_DISPATCHED.equals(type))                             dispatchedIds.add(itemId);
            if (TASK_COMPLETED.equals(type) || TASK_FAILED.equals(type)) terminatedIds.add(itemId);
        }

        // In-flight = dispatched but not yet terminated (channel state)
        Set<String> inFlightItemIds = new LinkedHashSet<>(dispatchedIds);
        inFlightItemIds.removeAll(terminatedIds);

        // ── Consistent cut verification ─────────────────────────────────
        // Chandy-Lamport invariant: every "receive" (COMPLETED/FAILED) must have
        // a corresponding "send" (DISPATCHED) in the cut
        Set<String> orphanedItemIds = new LinkedHashSet<>(terminatedIds);
        orphanedItemIds.removeAll(dispatchedIds);

        // State-event coherence: items in DISPATCHED/RUNNING must have a dispatch event
        List<String> coherenceViolations = new ArrayList<>();
        for (PlanItem item : items) {
            ItemStatus status = item.getStatus();
            if ((status == ItemStatus.DISPATCHED || status == ItemStatus.RUNNING)
                    && !dispatchedIds.contains(item.getId().toString())) {
                coherenceViolations.add(item.getTaskKey());
            }
        }

        boolean isConsistent = orphanedItemIds.isEmpty() && coherenceViolations.isEmpty();

        log.debug("ChandyLamport snapshot={} plan={} items={} completed={} inFlight={} "
                        + "orphaned={} coherenceViolations={} marker={} consistent={}",
                snapshotId, planId, items.size(), completedTaskKeys.size(),
                inFlightItemIds.size(), orphanedItemIds.size(),
                coherenceViolations.size(), markerSequence, isConsistent);

        return new SnapshotReport(
                snapshotId, planId, capturedAt, markerSequence,
                processStates,
                new ArrayList<>(completedTaskKeys),
                new ArrayList<>(inFlightItemIds),
                new ArrayList<>(orphanedItemIds),
                coherenceViolations,
                isConsistent
        );
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Local state of a single process (PlanItem) at snapshot time.
     */
    public record ProcessState(
            String taskKey,
            UUID itemId,
            ItemStatus status
    ) {}

    /**
     * Chandy-Lamport snapshot report.
     *
     * @param snapshotId           unique identifier for this snapshot
     * @param planId               the plan being snapshotted
     * @param capturedAt           wall-clock time when the snapshot was taken
     * @param markerSequence       sequence number of the last PlanEvent (logical marker position)
     * @param processStates        local state of each PlanItem (taskKey → status)
     * @param completedTaskKeys    task keys present in task_outcomes
     * @param inFlightItemIds      item UUIDs dispatched but not yet terminated (channel state)
     * @param orphanedItemIds      item UUIDs terminated without a prior dispatch event
     * @param coherenceViolations  task keys where materialized status conflicts with event history
     * @param isConsistent         true if no orphaned items AND no coherence violations
     */
    public record SnapshotReport(
            UUID snapshotId,
            UUID planId,
            Instant capturedAt,
            long markerSequence,
            Map<String, ProcessState> processStates,
            List<String> completedTaskKeys,
            List<String> inFlightItemIds,
            List<String> orphanedItemIds,
            List<String> coherenceViolations,
            boolean isConsistent
    ) {}
}
