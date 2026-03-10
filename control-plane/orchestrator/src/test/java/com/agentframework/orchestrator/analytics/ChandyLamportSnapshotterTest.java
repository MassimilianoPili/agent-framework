package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.eventsourcing.PlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventRepository;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
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
 * <p>Covers: null planId, empty plan, consistent snapshot with process states,
 * in-flight detection, orphaned task detection, marker sequence tracking,
 * state-event coherence violations.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChandyLamportSnapshotterTest {

    @Mock private PlanItemRepository    planItemRepository;
    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private PlanEventRepository   planEventRepository;

    private ChandyLamportSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        snapshotter = new ChandyLamportSnapshotter(
                planItemRepository, taskOutcomeRepository, planEventRepository);
    }

    /** Creates a minimal PlanEvent mock for the given itemId, eventType, and sequence. */
    private PlanEvent event(UUID itemId, String eventType, long sequenceNumber) {
        PlanEvent e = mock(PlanEvent.class);
        when(e.getItemId()).thenReturn(itemId);
        when(e.getEventType()).thenReturn(eventType);
        // lenient: only the last event's sequence is accessed (marker)
        lenient().when(e.getSequenceNumber()).thenReturn(sequenceNumber);
        return e;
    }

    /** Creates an outcome row: [workerProfile, reward, workerType, taskKey]. */
    private Object[] outcomeRow(String taskKey) {
        return new Object[]{"profile", 0.8, "be-java", taskKey};
    }

    /** Creates a PlanItem with the given id, taskKey, and status. */
    private PlanItem makeItem(UUID id, String taskKey, ItemStatus status) {
        PlanItem item = new PlanItem(id, 1, taskKey, "title", "desc",
                WorkerType.BE, "be-java", List.of(), List.of());
        // Set status via transitions from WAITING
        if (status == ItemStatus.DISPATCHED) {
            item.transitionTo(ItemStatus.DISPATCHED);
        } else if (status == ItemStatus.RUNNING) {
            item.transitionTo(ItemStatus.DISPATCHED);
            item.transitionTo(ItemStatus.RUNNING);
        } else if (status == ItemStatus.DONE) {
            item.transitionTo(ItemStatus.DISPATCHED);
            item.transitionTo(ItemStatus.RUNNING);
            item.transitionTo(ItemStatus.DONE);
        } else if (status == ItemStatus.FAILED) {
            item.transitionTo(ItemStatus.FAILED);
        }
        return item;
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
    @DisplayName("empty plan (no items, no events) → consistent empty snapshot with marker=0")
    void snapshot_emptyPlan_consistentEmpty() {
        UUID planId = UUID.randomUUID();

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of());
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(new ArrayList<>());
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(new ArrayList<>());

        var report = snapshotter.snapshot(planId);

        assertThat(report).isNotNull();
        assertThat(report.planId()).isEqualTo(planId);
        assertThat(report.processStates()).isEmpty();
        assertThat(report.completedTaskKeys()).isEmpty();
        assertThat(report.inFlightItemIds()).isEmpty();
        assertThat(report.orphanedItemIds()).isEmpty();
        assertThat(report.coherenceViolations()).isEmpty();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.markerSequence()).isEqualTo(0L);
    }

    // ── Consistent snapshot with process states ───────────────────────────────

    @Test
    @DisplayName("all dispatched items completed → consistent, process states captured")
    void snapshot_allCompleted_consistentWithStates() {
        UUID planId = UUID.randomUUID();
        UUID item1  = UUID.randomUUID();
        UUID item2  = UUID.randomUUID();

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(
                makeItem(item1, "task-1", ItemStatus.DONE),
                makeItem(item2, "task-2", ItemStatus.DONE)));

        List<Object[]> outcomes = new ArrayList<>();
        outcomes.add(outcomeRow("task-1"));
        outcomes.add(outcomeRow("task-2"));
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomes);

        List<PlanEvent> events = new ArrayList<>();
        events.add(event(item1, "TASK_DISPATCHED", 1));
        events.add(event(item1, "TASK_COMPLETED",  2));
        events.add(event(item2, "TASK_DISPATCHED", 3));
        events.add(event(item2, "TASK_COMPLETED",  4));
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

        var report = snapshotter.snapshot(planId);

        assertThat(report.inFlightItemIds()).isEmpty();
        assertThat(report.orphanedItemIds()).isEmpty();
        assertThat(report.coherenceViolations()).isEmpty();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.completedTaskKeys()).containsExactlyInAnyOrder("task-1", "task-2");
        assertThat(report.markerSequence()).isEqualTo(4L);

        // Process states reflect DONE
        assertThat(report.processStates()).hasSize(2);
        assertThat(report.processStates().get("task-1").status()).isEqualTo(ItemStatus.DONE);
        assertThat(report.processStates().get("task-2").status()).isEqualTo(ItemStatus.DONE);
    }

    // ── In-flight detection ───────────────────────────────────────────────────

    @Test
    @DisplayName("dispatched but not terminated item → in-flight channel state")
    void snapshot_inFlightTask_detectedInChannel() {
        UUID planId    = UUID.randomUUID();
        UUID inFlight  = UUID.randomUUID();
        UUID completed = UUID.randomUUID();

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(
                makeItem(completed, "task-done", ItemStatus.DONE),
                makeItem(inFlight,  "task-running", ItemStatus.RUNNING)));

        List<Object[]> outcomes = new ArrayList<>();
        outcomes.add(outcomeRow("task-done"));
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomes);

        List<PlanEvent> events = new ArrayList<>();
        events.add(event(completed, "TASK_DISPATCHED", 1));
        events.add(event(completed, "TASK_COMPLETED",  2));
        events.add(event(inFlight,  "TASK_DISPATCHED", 3));
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

        var report = snapshotter.snapshot(planId);

        assertThat(report.inFlightItemIds()).containsExactly(inFlight.toString());
        assertThat(report.isConsistent()).isTrue(); // no orphans, no coherence violations
        assertThat(report.markerSequence()).isEqualTo(3L);
        assertThat(report.processStates().get("task-running").status()).isEqualTo(ItemStatus.RUNNING);
    }

    // ── Orphaned task detection ───────────────────────────────────────────────

    @Test
    @DisplayName("completed without dispatch → orphaned item, inconsistent snapshot")
    void snapshot_orphanedItem_inconsistent() {
        UUID planId = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(
                makeItem(orphan, "task-orphan", ItemStatus.DONE)));
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(new ArrayList<>());

        List<PlanEvent> events = new ArrayList<>();
        events.add(event(orphan, "TASK_COMPLETED", 1));  // completed without prior dispatch
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

        var report = snapshotter.snapshot(planId);

        assertThat(report.orphanedItemIds()).containsExactly(orphan.toString());
        assertThat(report.isConsistent()).isFalse();
    }

    // ── State-event coherence violation ───────────────────────────────────────

    @Test
    @DisplayName("DISPATCHED item without dispatch event → coherence violation")
    void snapshot_coherenceViolation_dispatchedWithoutEvent() {
        UUID planId = UUID.randomUUID();
        UUID item   = UUID.randomUUID();

        // Item is in DISPATCHED state but no TASK_DISPATCHED event exists
        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(
                makeItem(item, "task-ghost", ItemStatus.DISPATCHED)));
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(new ArrayList<>());
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(new ArrayList<>());

        var report = snapshotter.snapshot(planId);

        assertThat(report.coherenceViolations()).containsExactly("task-ghost");
        assertThat(report.isConsistent()).isFalse();
    }

    // ── Failed items ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("failed item (TASK_FAILED) counts as terminated, not in-flight")
    void snapshot_failedItem_notInFlight() {
        UUID planId = UUID.randomUUID();
        UUID item   = UUID.randomUUID();

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(
                makeItem(item, "task-fail", ItemStatus.FAILED)));
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(new ArrayList<>());

        List<PlanEvent> events = new ArrayList<>();
        events.add(event(item, "TASK_DISPATCHED", 1));
        events.add(event(item, "TASK_FAILED",     2));
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

        var report = snapshotter.snapshot(planId);

        assertThat(report.inFlightItemIds()).isEmpty();
        assertThat(report.orphanedItemIds()).isEmpty();
        assertThat(report.isConsistent()).isTrue();
        assertThat(report.markerSequence()).isEqualTo(2L);
    }

    // ── Mixed states ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("mixed plan: WAITING + DISPATCHED + DONE → captures all process states")
    void snapshot_mixedStates_capturesAll() {
        UUID planId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(
                makeItem(id1, "task-done",     ItemStatus.DONE),
                makeItem(id2, "task-running",  ItemStatus.RUNNING),
                makeItem(id3, "task-waiting",  ItemStatus.WAITING)));
        List<Object[]> outcomesList = new ArrayList<>();
        outcomesList.add(outcomeRow("task-done"));
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomesList);

        List<PlanEvent> events = new ArrayList<>();
        events.add(event(id1, "TASK_DISPATCHED", 1));
        events.add(event(id1, "TASK_COMPLETED",  2));
        events.add(event(id2, "TASK_DISPATCHED", 3));
        when(planEventRepository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(events);

        var report = snapshotter.snapshot(planId);

        assertThat(report.processStates()).hasSize(3);
        assertThat(report.processStates().get("task-done").status()).isEqualTo(ItemStatus.DONE);
        assertThat(report.processStates().get("task-running").status()).isEqualTo(ItemStatus.RUNNING);
        assertThat(report.processStates().get("task-waiting").status()).isEqualTo(ItemStatus.WAITING);
        assertThat(report.inFlightItemIds()).containsExactly(id2.toString());
        assertThat(report.isConsistent()).isTrue();
    }
}
