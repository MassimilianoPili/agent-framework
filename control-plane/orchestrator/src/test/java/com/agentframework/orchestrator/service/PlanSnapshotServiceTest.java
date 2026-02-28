package com.agentframework.orchestrator.service;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.repository.PlanSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlanSnapshotService}.
 * Focuses on restore() with forceStatus() bypass of the state machine.
 */
@ExtendWith(MockitoExtension.class)
class PlanSnapshotServiceTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanItemRepository planItemRepository;
    @Mock private PlanSnapshotRepository snapshotRepository;

    private PlanSnapshotService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID SNAPSHOT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PlanSnapshotService(planRepository, planItemRepository, snapshotRepository, objectMapper);
    }

    // ── snapshot() ────────────────────────────────────────────────────────────

    @Test
    void snapshot_capturesPlanAndItems() {
        Plan plan = createPlan(PlanStatus.RUNNING);
        PlanItem item = createItem(plan, "T1", ItemStatus.DONE);

        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findByPlanId(PLAN_ID)).thenReturn(List.of(item));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PlanSnapshot snapshot = service.snapshot(PLAN_ID, "before-fix");

        assertThat(snapshot.getLabel()).isEqualTo("before-fix");
        assertThat(snapshot.getPlanData()).contains("\"status\":\"RUNNING\"");
        assertThat(snapshot.getPlanData()).contains("\"status\":\"DONE\"");
    }

    @Test
    void snapshot_planNotFound_throws() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.snapshot(PLAN_ID, "test"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Plan not found");
    }

    // ── restore() with forceStatus ───────────────────────────────────────────

    @Test
    void restore_completedToRunning_succeeds() {
        // This was the bug: COMPLETED→RUNNING is illegal via transitionTo()
        Plan plan = createPlan(PlanStatus.COMPLETED);
        UUID itemId = UUID.randomUUID();
        PlanItem item = createItemWithId(plan, itemId, "T1", ItemStatus.DONE);

        String snapshotData = buildSnapshotJson(PlanStatus.RUNNING, List.of(
            new ItemSnapshot(itemId, "T1", ItemStatus.WAITING, WorkerType.BE, "be-java")
        ));
        PlanSnapshot snapshot = new PlanSnapshot(SNAPSHOT_ID, plan, "checkpoint", snapshotData);

        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        service.restore(SNAPSHOT_ID);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(item.getStatus()).isEqualTo(ItemStatus.WAITING);
        verify(planRepository).save(plan);
        verify(planItemRepository).save(item);
    }

    @Test
    void restore_doneToWaiting_succeeds() {
        Plan plan = createPlan(PlanStatus.RUNNING);
        UUID itemId = UUID.randomUUID();
        PlanItem item = createItemWithId(plan, itemId, "T1", ItemStatus.DONE);

        String snapshotData = buildSnapshotJson(PlanStatus.RUNNING, List.of(
            new ItemSnapshot(itemId, "T1", ItemStatus.WAITING, WorkerType.BE, "be-java")
        ));
        PlanSnapshot snapshot = new PlanSnapshot(SNAPSHOT_ID, plan, "rollback", snapshotData);

        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        service.restore(SNAPSHOT_ID);

        assertThat(item.getStatus()).isEqualTo(ItemStatus.WAITING);
    }

    @Test
    void restore_mixedItemStates_restoresAll() {
        Plan plan = createPlan(PlanStatus.COMPLETED);
        UUID item1Id = UUID.randomUUID();
        UUID item2Id = UUID.randomUUID();
        PlanItem item1 = createItemWithId(plan, item1Id, "T1", ItemStatus.DONE);
        PlanItem item2 = createItemWithId(plan, item2Id, "T2", ItemStatus.FAILED);

        String snapshotData = buildSnapshotJson(PlanStatus.RUNNING, List.of(
            new ItemSnapshot(item1Id, "T1", ItemStatus.RUNNING, WorkerType.BE, "be-java"),
            new ItemSnapshot(item2Id, "T2", ItemStatus.WAITING, WorkerType.FE, "fe-react")
        ));
        PlanSnapshot snapshot = new PlanSnapshot(SNAPSHOT_ID, plan, "mixed", snapshotData);

        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findById(item1Id)).thenReturn(Optional.of(item1));
        when(planItemRepository.findById(item2Id)).thenReturn(Optional.of(item2));

        service.restore(SNAPSHOT_ID);

        assertThat(plan.getStatus()).isEqualTo(PlanStatus.RUNNING);
        assertThat(item1.getStatus()).isEqualTo(ItemStatus.RUNNING);
        assertThat(item2.getStatus()).isEqualTo(ItemStatus.WAITING);
    }

    @Test
    void restore_restoresResultAndFailureReason() {
        Plan plan = createPlan(PlanStatus.RUNNING);
        UUID itemId = UUID.randomUUID();
        PlanItem item = createItemWithId(plan, itemId, "T1", ItemStatus.WAITING);

        String snapshotData = """
            {"planId":"%s","status":"RUNNING","items":[
                {"id":"%s","taskKey":"T1","status":"DONE","workerType":"BE","workerProfile":"be-java",
                 "result":"some output","failureReason":null}
            ]}""".formatted(PLAN_ID, itemId);
        PlanSnapshot snapshot = new PlanSnapshot(SNAPSHOT_ID, plan, "with-result", snapshotData);

        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        service.restore(SNAPSHOT_ID);

        assertThat(item.getResult()).isEqualTo("some output");
        assertThat(item.getFailureReason()).isNull();
    }

    @Test
    void restore_missingItemInDb_skippedSilently() {
        Plan plan = createPlan(PlanStatus.RUNNING);
        UUID missingItemId = UUID.randomUUID();

        String snapshotData = buildSnapshotJson(PlanStatus.RUNNING, List.of(
            new ItemSnapshot(missingItemId, "T1", ItemStatus.DONE, WorkerType.BE, "be-java")
        ));
        PlanSnapshot snapshot = new PlanSnapshot(SNAPSHOT_ID, plan, "orphan", snapshotData);

        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(planItemRepository.findById(missingItemId)).thenReturn(Optional.empty());

        // Should not throw
        service.restore(SNAPSHOT_ID);

        verify(planRepository).save(plan);
        verify(planItemRepository, never()).save(any());
    }

    @Test
    void restore_snapshotNotFound_throws() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(SNAPSHOT_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Snapshot not found");
    }

    @Test
    void restore_planNotFound_throws() {
        Plan dummyPlan = createPlan(PlanStatus.RUNNING);
        String snapshotData = buildSnapshotJson(PlanStatus.RUNNING, List.of());
        PlanSnapshot snapshot = new PlanSnapshot(SNAPSHOT_ID, dummyPlan, "bad", snapshotData);

        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(SNAPSHOT_ID))
            .isInstanceOf(RuntimeException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Plan createPlan(PlanStatus status) {
        Plan plan = new Plan(PLAN_ID, "test spec");
        plan.forceStatus(status);
        return plan;
    }

    private PlanItem createItem(Plan plan, String taskKey, ItemStatus status) {
        PlanItem item = new PlanItem(UUID.randomUUID(), 1, taskKey, "Title",
            "Desc", WorkerType.BE, "be-java", List.of());
        plan.addItem(item);
        item.forceStatus(status);
        return item;
    }

    private PlanItem createItemWithId(Plan plan, UUID id, String taskKey, ItemStatus status) {
        PlanItem item = new PlanItem(id, 1, taskKey, "Title",
            "Desc", WorkerType.BE, "be-java", List.of());
        plan.addItem(item);
        item.forceStatus(status);
        return item;
    }

    private record ItemSnapshot(UUID id, String taskKey, ItemStatus status, WorkerType workerType, String profile) {}

    private String buildSnapshotJson(PlanStatus planStatus, List<ItemSnapshot> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"planId\":\"").append(PLAN_ID).append("\",");
        sb.append("\"status\":\"").append(planStatus.name()).append("\",");
        sb.append("\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            ItemSnapshot item = items.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(item.id()).append("\",");
            sb.append("\"taskKey\":\"").append(item.taskKey()).append("\",");
            sb.append("\"status\":\"").append(item.status().name()).append("\",");
            sb.append("\"workerType\":\"").append(item.workerType().name()).append("\",");
            sb.append("\"workerProfile\":\"").append(item.profile()).append("\",");
            sb.append("\"result\":null,\"failureReason\":null}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
