package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.eventsourcing.PlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventRepository;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChandyLamportSnapshotter}.
 *
 * <p>Covers: null planId, empty plan, consistent snapshot,
 * in-flight detection, orphaned task detection (inconsistency).</p>
 */
@ExtendWith(MockitoExtension.class)
class ChandyLamportSnapshotterTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private PlanEventRepository   planEventRepository;

    private ChandyLamportSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        snapshotter = new ChandyLamportSnapshotter(taskOutcomeRepository, planEventRepository);
    }

    /** Creates a minimal PlanEvent mock for the given itemId and eventType. */
    private PlanEvent event(UUID itemId, String eventType) {
        PlanEvent e = mock(PlanEvent.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getEventType()).thenReturn(eventType);
        return e;
    }

    /** Creates an outcome row: [workerProfile, reward, workerType, taskKey]. */
    private Object[] outcomeRow(String taskKey) {
        return new Object[]{"profile", 0.8, "be-java", taskKey};
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("throws NullPointerException for null planId")
    void snapshot_nullPlanId_throws() {
        assertThatThrownBy(() -> snapshotter.snapshot(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Empty plan ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("empty plan (no outcomes, no events) → consistent empty snapshot")
    void snapshot_emptyPlan_consistentEmpty() {
        UUID planId = UUID.randomUUID();
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(List.of());
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of());

        ChandyLamportSnapshotter.SnapshotReport report = snapshotter.snapshot(planId);

        assertThat(report).isNotNull();
        assertThat(report.planId()).isEqualTo(planId);
        assertThat(report.completedTaskKeys()).isEmpty();
        assertThat(report.inFlightItemIds()).isEmpty();
        assertThat(report.orphanedItemIds()).isEmpty();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.snapshotId()).isNotNull();
        assertThat(report.capturedAt()).isNotNull();
    }

    // ── Consistent snapshot ───────────────────────────────────────────────────

    @Test
    @DisplayName("all dispatched items are completed → no in-flight, consistent")
    void snapshot_allCompleted_consistent() {
        UUID planId = UUID.randomUUID();
        UUID item1  = UUID.randomUUID();
        UUID item2  = UUID.randomUUID();

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(
                List.of(outcomeRow("task-1"), outcomeRow("task-2")));
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(item1, "TASK_DISPATCHED"),
                event(item1, "TASK_COMPLETED"),
                event(item2, "TASK_DISPATCHED"),
                event(item2, "TASK_COMPLETED")
        ));

        ChandyLamportSnapshotter.SnapshotReport report = snapshotter.snapshot(planId);

        assertThat(report.inFlightItemIds()).isEmpty();
        assertThat(report.orphanedItemIds()).isEmpty();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.completedTaskKeys()).containsExactlyInAnyOrder("task-1", "task-2");
    }

    // ── In-flight detection ───────────────────────────────────────────────────

    @Test
    @DisplayName("dispatched but not terminated item → appears in inFlightItemIds")
    void snapshot_inFlightTask_detectedInChannel() {
        UUID planId    = UUID.randomUUID();
        UUID inFlight  = UUID.randomUUID();
        UUID completed = UUID.randomUUID();

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(
                List.of(outcomeRow("task-completed")));
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(completed, "TASK_DISPATCHED"),
                event(completed, "TASK_COMPLETED"),
                event(inFlight,  "TASK_DISPATCHED")     // dispatched but no COMPLETED yet
        ));

        ChandyLamportSnapshotter.SnapshotReport report = snapshotter.snapshot(planId);

        assertThat(report.inFlightItemIds()).containsExactly(inFlight.toString());
        assertThat(report.isConsistent()).isTrue();  // no orphans
    }

    // ── Orphaned task detection ───────────────────────────────────────────────

    @Test
    @DisplayName("completed without dispatch → orphaned item, inconsistent snapshot")
    void snapshot_orphanedItem_inconsistent() {
        UUID planId  = UUID.randomUUID();
        UUID orphan  = UUID.randomUUID();

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(List.of());
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(orphan, "TASK_COMPLETED")  // completed without prior dispatch
        ));

        ChandyLamportSnapshotter.SnapshotReport report = snapshotter.snapshot(planId);

        assertThat(report.orphanedItemIds()).containsExactly(orphan.toString());
        assertThat(report.isConsistent()).isFalse();
    }

    // ── Failed items ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("failed item (TASK_FAILED) counts as terminated, not in-flight")
    void snapshot_failedItem_notInFlight() {
        UUID planId = UUID.randomUUID();
        UUID item   = UUID.randomUUID();

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(List.of());
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(
                event(item, "TASK_DISPATCHED"),
                event(item, "TASK_FAILED")
        ));

        ChandyLamportSnapshotter.SnapshotReport report = snapshotter.snapshot(planId);

        assertThat(report.inFlightItemIds()).isEmpty();
        assertThat(report.orphanedItemIds()).isEmpty();
        assertThat(report.isConsistent()).isTrue();
    }
}
