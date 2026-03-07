package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedispatchPollerService}.
 * Verifies that TO_DISPATCH items are picked up and redispatched,
 * empty lists cause no-ops, and failures in one item don't block others.
 */
@ExtendWith(MockitoExtension.class)
class RedispatchPollerServiceTest {

    @Mock private PlanItemRepository planItemRepository;
    @Mock private RedispatchTransactionService redispatchTransactionService;

    private RedispatchPollerService poller;

    @BeforeEach
    void setUp() {
        poller = new RedispatchPollerService(planItemRepository, redispatchTransactionService);
    }

    @Test
    void pollToDispatch_emptyList_doesNothing() {
        when(planItemRepository.findByStatusWithPlan(ItemStatus.TO_DISPATCH))
            .thenReturn(List.of());

        poller.pollToDispatch();

        verifyNoInteractions(redispatchTransactionService);
    }

    @Test
    void pollToDispatch_itemsFound_redispatchesEach() {
        PlanItem item1 = createItemInPlan("BE-001");
        PlanItem item2 = createItemInPlan("FE-001");

        when(planItemRepository.findByStatusWithPlan(ItemStatus.TO_DISPATCH))
            .thenReturn(List.of(item1, item2));

        poller.pollToDispatch();

        verify(redispatchTransactionService).redispatchItem(item1.getId());
        verify(redispatchTransactionService).redispatchItem(item2.getId());
    }

    @Test
    void pollToDispatch_failureInOneItem_doesNotBlockOthers() {
        PlanItem item1 = createItemInPlan("BE-001");
        PlanItem item2 = createItemInPlan("FE-001");

        when(planItemRepository.findByStatusWithPlan(ItemStatus.TO_DISPATCH))
            .thenReturn(List.of(item1, item2));
        doThrow(new RuntimeException("dispatch failed"))
            .when(redispatchTransactionService).redispatchItem(item1.getId());

        poller.pollToDispatch();

        // item2 should still be redispatched despite item1 failure
        verify(redispatchTransactionService).redispatchItem(item2.getId());
    }

    private PlanItem createItemInPlan(String taskKey) {
        Plan plan = new Plan(UUID.randomUUID(), "test spec");
        plan.transitionTo(PlanStatus.RUNNING);
        PlanItem item = new PlanItem(
            UUID.randomUUID(), 1, taskKey, "Title " + taskKey,
            "Description", WorkerType.BE, null, List.of(), List.of()
        );
        item.forceStatus(ItemStatus.TO_DISPATCH);
        plan.addItem(item);
        return item;
    }
}
