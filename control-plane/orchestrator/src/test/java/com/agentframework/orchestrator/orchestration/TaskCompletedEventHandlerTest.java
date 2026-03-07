package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.event.TaskCompletedSideEffectEvent;
import com.agentframework.orchestrator.gp.SerendipityService;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.hooks.HookManagerService;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.reward.RewardComputationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TaskCompletedEventHandler} — side-effect isolation
 * after task completion. Each side-effect must be independent: a failure in
 * one must not prevent others from running.
 */
@ExtendWith(MockitoExtension.class)
class TaskCompletedEventHandlerTest {

    @Mock private PlanItemRepository planItemRepository;
    @Mock private RewardComputationService rewardComputationService;
    @Mock private TaskOutcomeService gpTaskOutcomeService;
    @Mock private SerendipityService serendipityService;
    @Mock private HookManagerService hookManagerService;

    private TaskCompletedEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TaskCompletedEventHandler(
                planItemRepository, rewardComputationService,
                gpTaskOutcomeService, serendipityService, hookManagerService);
    }

    @Test
    void handleSideEffects_success_computesProcessScore() {
        PlanItem item = createItemWithPlan(WorkerType.BE, "BE-001");
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);

        AgentResult result = successResult(item.getPlan().getId(), item.getId(), "BE-001");

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));

        handler.handleSideEffects(new TaskCompletedSideEffectEvent(item.getId(), result));

        verify(rewardComputationService).computeProcessScore(item, result);
    }

    @Test
    void handleSideEffects_reviewWorker_distributesReviewScore() {
        PlanItem item = createItemWithPlan(WorkerType.REVIEW, "RV-001");
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);

        AgentResult result = successResult(item.getPlan().getId(), item.getId(), "RV-001");

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));

        handler.handleSideEffects(new TaskCompletedSideEffectEvent(item.getId(), result));

        verify(rewardComputationService).distributeReviewScore(item);
    }

    @Test
    void handleSideEffects_hookManager_storesPolicies() {
        PlanItem item = createItemWithPlan(WorkerType.HOOK_MANAGER, "HM-001");
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);

        String policiesJson = "{\"policies\": {}}";
        UUID planId = item.getPlan().getId();
        AgentResult result = new AgentResult(planId, item.getId(), "HM-001", true,
                policiesJson, null, 500L, "HOOK_MANAGER", null, null, null, null, null);

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));

        handler.handleSideEffects(new TaskCompletedSideEffectEvent(item.getId(), result));

        verify(hookManagerService).storePolicies(planId, policiesJson);
    }

    @Test
    void handleSideEffects_oneFailure_otherSideEffectsStillRun() {
        PlanItem item = createItemWithPlan(WorkerType.REVIEW, "RV-001");
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);

        AgentResult result = successResult(item.getPlan().getId(), item.getId(), "RV-001");

        when(planItemRepository.findByIdWithPlan(item.getId())).thenReturn(Optional.of(item));
        // computeProcessScore throws — should not prevent distributeReviewScore
        doThrow(new RuntimeException("DB error"))
                .when(rewardComputationService).computeProcessScore(any(), any());

        handler.handleSideEffects(new TaskCompletedSideEffectEvent(item.getId(), result));

        // distributeReviewScore should still be called despite computeProcessScore failure
        verify(rewardComputationService).distributeReviewScore(item);
    }

    @Test
    void handleSideEffects_itemNotFound_skipsGracefully() {
        UUID missingId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        AgentResult result = successResult(planId, missingId, "BE-001");

        when(planItemRepository.findByIdWithPlan(missingId)).thenReturn(Optional.empty());

        // Should not throw
        handler.handleSideEffects(new TaskCompletedSideEffectEvent(missingId, result));

        verifyNoInteractions(rewardComputationService);
        verifyNoInteractions(hookManagerService);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private PlanItem createItemWithPlan(WorkerType type, String taskKey) {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);
        PlanItem item = new PlanItem(UUID.randomUUID(), 0, taskKey, "Test: " + taskKey,
                "Description", type, null, List.of(), List.of());
        plan.addItem(item);
        return item;
    }

    private AgentResult successResult(UUID planId, UUID itemId, String taskKey) {
        return new AgentResult(planId, itemId, taskKey, true, "{\"ok\":true}", null,
                500L, "BE", null, null, null, null, null);
    }
}
