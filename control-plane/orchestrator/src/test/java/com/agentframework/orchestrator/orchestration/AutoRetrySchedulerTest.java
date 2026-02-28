package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AutoRetryScheduler} — retry eligibility polling,
 * per-item error isolation, and nextRetryAt clearing.
 */
@ExtendWith(MockitoExtension.class)
class AutoRetrySchedulerTest {

    @Mock
    private PlanItemRepository planItemRepository;

    @Mock
    private OrchestrationService orchestrationService;

    @InjectMocks
    private AutoRetryScheduler scheduler;

    // ── retryEligibleItems ───────────────────────────────────────────────────

    @Test
    void retryEligibleItems_noEligibleItems_doesNotCallOrchestrationService() {
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of());

        scheduler.retryEligibleItems();

        verify(planItemRepository).findRetryEligible(any(Instant.class));
        verifyNoInteractions(orchestrationService);
        verify(planItemRepository, never()).save(any());
    }

    @Test
    void retryEligibleItems_oneEligibleItem_retriesWithCorrectId() {
        PlanItem item = createFailedItem();
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of(item));

        scheduler.retryEligibleItems();

        verify(orchestrationService).retryFailedItem(item.getId());
        verify(planItemRepository).save(item);
    }

    @Test
    void retryEligibleItems_multipleEligibleItems_allRetried() {
        PlanItem item1 = createFailedItem();
        PlanItem item2 = createFailedItem();
        PlanItem item3 = createFailedItem();
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of(item1, item2, item3));

        scheduler.retryEligibleItems();

        verify(orchestrationService).retryFailedItem(item1.getId());
        verify(orchestrationService).retryFailedItem(item2.getId());
        verify(orchestrationService).retryFailedItem(item3.getId());
        verify(planItemRepository, times(3)).save(any(PlanItem.class));
    }

    @Test
    void retryEligibleItems_oneItemThrowsException_otherItemsStillRetried() {
        PlanItem item1 = createFailedItem();
        PlanItem item2 = createFailedItem();
        PlanItem item3 = createFailedItem();
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of(item1, item2, item3));

        // item2 throws during retry
        doNothing().when(orchestrationService).retryFailedItem(item1.getId());
        doThrow(new RuntimeException("Transient failure"))
                .when(orchestrationService).retryFailedItem(item2.getId());
        doNothing().when(orchestrationService).retryFailedItem(item3.getId());

        scheduler.retryEligibleItems();

        // All three were attempted
        verify(orchestrationService).retryFailedItem(item1.getId());
        verify(orchestrationService).retryFailedItem(item2.getId());
        verify(orchestrationService).retryFailedItem(item3.getId());
    }

    @Test
    void retryEligibleItems_nextRetryAtClearedBeforeRetry() {
        PlanItem item = createFailedItem();
        Instant originalRetryAt = item.getNextRetryAt();
        assertThat(originalRetryAt).isNotNull();

        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of(item));

        scheduler.retryEligibleItems();

        // Verify nextRetryAt was cleared
        assertThat(item.getNextRetryAt()).isNull();

        // Verify save happens before retryFailedItem (ordering)
        InOrder inOrder = inOrder(planItemRepository, orchestrationService);
        inOrder.verify(planItemRepository).save(item);
        inOrder.verify(orchestrationService).retryFailedItem(item.getId());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Creates a PlanItem in FAILED status with nextRetryAt in the past.
     * State transitions: WAITING -> FAILED (allowed by the state machine).
     */
    private PlanItem createFailedItem() {
        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, "BE-" + UUID.randomUUID().toString().substring(0, 3),
                "Test task", "Test description", WorkerType.BE, "be-java", List.of()
        );
        item.transitionTo(ItemStatus.FAILED);
        item.setNextRetryAt(Instant.now().minusSeconds(10));
        return item;
    }
}
