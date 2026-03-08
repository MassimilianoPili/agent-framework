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

import java.util.ArrayList;
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

        List<Object[]> noOutcomes = new ArrayList<>();
        List<PlanEvent> noEvents  = new ArrayList<>();
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(noOutcomes);
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(noEvents);

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

        List<Object[]> outcomes = new ArrayList<>();
        outcomes.add(outcomeRow("task-1"));
        outcomes.add(outcomeRow("task-2"));

        List<PlanEvent> events = new ArrayList<>();
        events.add(event(item1, "TASK_DISPATCHED"));
        events.add(event(item1, "TASK_COMPLETED"));
        events.add(event(item2, "TASK_DISPATCHED"));
        events.add(event(item2, "TASK_COMPLETED"));

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomes);
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

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

        List<Object[]> outcomes = new ArrayList<>();
        outcomes.add(outcomeRow("task-completed"));

        List<PlanEvent> events = new ArrayList<>();
        events.add(event(completed, "TASK_DISPATCHED"));
        events.add(event(completed, "TASK_COMPLETED"));
        events.add(event(inFlight,  "TASK_DISPATCHED"));   // dispatched but no COMPLETED yet

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomes);
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

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

        List<Object[]> noOutcomes = new ArrayList<>();
        List<PlanEvent> events    = new ArrayList<>();
        events.add(event(orphan, "TASK_COMPLETED"));   // completed without prior dispatch

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(noOutcomes);
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

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

        List<Object[]> noOutcomes = new ArrayList<>();
        List<PlanEvent> events    = new ArrayList<>();
        events.add(event(item, "TASK_DISPATCHED"));
        events.add(event(item, "TASK_FAILED"));

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(noOutcomes);
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

        ChandyLamportSnapshotter.SnapshotReport report = snapshotter.snapshot(planId);

        assertThat(report.inFlightItemIds()).isEmpty();
        assertThat(report.orphanedItemIds()).isEmpty();
        assertThat(report.isConsistent()).isTrue();
    }
}
