package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.config.StaleDetectorProperties;
import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StaleTaskDetectorScheduler} — stale DISPATCHED task detection.
 */
@ExtendWith(MockitoExtension.class)
class StaleTaskDetectorSchedulerTest {

    @Mock private PlanItemRepository planItemRepository;

    private StaleTaskDetectorScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Default 30 min timeout for all worker types (no per-type overrides)
        var staleProps = new StaleDetectorProperties(30, Map.of());
        scheduler = new StaleTaskDetectorScheduler(planItemRepository, staleProps);
    }

    @Test
    void detectStaleTasks_staleItemFound_markedAsFailed() {
        PlanItem item = createDispatchedItem();
        when(planItemRepository.findStaleDispatched(any(Instant.class)))
                .thenReturn(List.of(item));
        when(planItemRepository.save(any(PlanItem.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.detectStaleTasks();

        assertThat(item.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("stale_timeout");
        assertThat(item.getCompletedAt()).isNotNull();
        verify(planItemRepository).save(item);
    }

    @Test
    void detectStaleTasks_noStaleItems_noSavesCalled() {
        when(planItemRepository.findStaleDispatched(any(Instant.class)))
                .thenReturn(List.of());

        scheduler.detectStaleTasks();

        verify(planItemRepository, never()).save(any());
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private PlanItem createDispatchedItem() {
        Plan plan = new Plan(UUID.randomUUID(), "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);
        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, "BE-001", "Stale task", "desc",
                WorkerType.BE, "be-java", List.of(), List.of()
        );
        plan.addItem(item);
        item.transitionTo(ItemStatus.DISPATCHED);
        item.setDispatchedAt(Instant.now().minusSeconds(3600)); // 1 hour ago (> 30 min default)
        return item;
    }
}
