package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AutoRetryScheduler} — retry eligibility polling,
 * per-item error isolation via {@link RetryTransactionService}.
 */
@ExtendWith(MockitoExtension.class)
class AutoRetrySchedulerTest {

    @Mock
    private PlanItemRepository planItemRepository;

    @Mock
    private RetryTransactionService retryTransactionService;

    @InjectMocks
    private AutoRetryScheduler scheduler;

    // ── retryEligibleItems ───────────────────────────────────────────────────

    @Test
    void retryEligibleItems_noEligibleItems_doesNotCallRetryService() {
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of());

        scheduler.retryEligibleItems();

        verify(planItemRepository).findRetryEligible(any(Instant.class));
        verifyNoInteractions(retryTransactionService);
    }

    @Test
    void retryEligibleItems_oneEligibleItem_retriesWithCorrectId() {
        PlanItem item = createFailedItem();
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of(item));

        scheduler.retryEligibleItems();

        verify(retryTransactionService).retryItem(item.getId());
    }

    @Test
    void retryEligibleItems_multipleEligibleItems_allRetried() {
        PlanItem item1 = createFailedItem();
        PlanItem item2 = createFailedItem();
        PlanItem item3 = createFailedItem();
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of(item1, item2, item3));

        scheduler.retryEligibleItems();

        verify(retryTransactionService).retryItem(item1.getId());
        verify(retryTransactionService).retryItem(item2.getId());
        verify(retryTransactionService).retryItem(item3.getId());
    }

    @Test
    void retryEligibleItems_oneItemThrowsException_otherItemsStillRetried() {
        PlanItem item1 = createFailedItem();
        PlanItem item2 = createFailedItem();
        PlanItem item3 = createFailedItem();
        when(planItemRepository.findRetryEligible(any(Instant.class)))
                .thenReturn(List.of(item1, item2, item3));

        // item2 throws during retry
        doNothing().when(retryTransactionService).retryItem(item1.getId());
        doThrow(new RuntimeException("Transient failure"))
                .when(retryTransactionService).retryItem(item2.getId());
        doNothing().when(retryTransactionService).retryItem(item3.getId());

        scheduler.retryEligibleItems();

        // All three were attempted despite item2 failure
        verify(retryTransactionService).retryItem(item1.getId());
        verify(retryTransactionService).retryItem(item2.getId());
        verify(retryTransactionService).retryItem(item3.getId());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private PlanItem createFailedItem() {
        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, "BE-" + UUID.randomUUID().toString().substring(0, 3),
                "Test task", "Test description", WorkerType.BE, "be-java", List.of(), List.of()
        );
        item.transitionTo(ItemStatus.FAILED);
        item.setNextRetryAt(Instant.now().minusSeconds(10));
        return item;
    }
}
