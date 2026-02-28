package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.api.dto.PlanRequest.Budget;
import com.agentframework.orchestrator.budget.TokenBudgetService.BudgetDecision;
import com.agentframework.orchestrator.budget.TokenBudgetService.BudgetDecision.Action;
import com.agentframework.orchestrator.domain.PlanTokenUsage;
import com.agentframework.orchestrator.repository.PlanTokenUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TokenBudgetService} — budget checking, usage recording,
 * and current-usage retrieval.
 */
@ExtendWith(MockitoExtension.class)
class TokenBudgetServiceTest {

    @Mock
    private PlanTokenUsageRepository usageRepository;

    @InjectMocks
    private TokenBudgetService service;

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final String WORKER_TYPE_BE = "BE";

    // ── checkBudget ──────────────────────────────────────────────────────────

    @Test
    void checkBudget_nullBudget_returnsAllow() {
        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, null);

        assertThat(decision.action()).isEqualTo(Action.ALLOW);
        assertThat(decision.isBlocked()).isFalse();
        verifyNoInteractions(usageRepository);
    }

    @Test
    void checkBudget_nullPerWorkerTypeMap_returnsAllow() {
        Budget budget = new Budget("FAIL_FAST", null);

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.ALLOW);
        verifyNoInteractions(usageRepository);
    }

    @Test
    void checkBudget_workerTypeNotInPerWorkerType_returnsAllow() {
        Budget budget = new Budget("FAIL_FAST", Map.of("FE", 50000L));

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.ALLOW);
        // currentUsage is never called because the worker type has no limit
        verifyNoInteractions(usageRepository);
    }

    @Test
    void checkBudget_usageBelowLimit_returnsAllow() {
        Budget budget = new Budget("FAIL_FAST", Map.of(WORKER_TYPE_BE, 50000L));
        stubCurrentUsage(PLAN_ID, WORKER_TYPE_BE, 30000L);

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.ALLOW);
        assertThat(decision.isBlocked()).isFalse();
    }

    @Test
    void checkBudget_usageAtLimit_failFast_returnsFail() {
        Budget budget = new Budget("FAIL_FAST", Map.of(WORKER_TYPE_BE, 50000L));
        stubCurrentUsage(PLAN_ID, WORKER_TYPE_BE, 50000L);

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.FAIL);
        assertThat(decision.isBlocked()).isTrue();
        assertThat(decision.used()).isEqualTo(50000L);
        assertThat(decision.limit()).isEqualTo(50000L);
    }

    @Test
    void checkBudget_usageAboveLimit_failFast_returnsFail() {
        Budget budget = new Budget("FAIL_FAST", Map.of(WORKER_TYPE_BE, 50000L));
        stubCurrentUsage(PLAN_ID, WORKER_TYPE_BE, 60000L);

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.FAIL);
        assertThat(decision.isBlocked()).isTrue();
    }

    @Test
    void checkBudget_usageAtLimit_noNewDispatch_returnsSkip() {
        Budget budget = new Budget("NO_NEW_DISPATCH", Map.of(WORKER_TYPE_BE, 50000L));
        stubCurrentUsage(PLAN_ID, WORKER_TYPE_BE, 50000L);

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.SKIP);
        assertThat(decision.isBlocked()).isTrue();
    }

    @Test
    void checkBudget_usageAtLimit_softLimit_returnsWarn() {
        Budget budget = new Budget("SOFT_LIMIT", Map.of(WORKER_TYPE_BE, 50000L));
        stubCurrentUsage(PLAN_ID, WORKER_TYPE_BE, 50000L);

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.WARN);
        assertThat(decision.isBlocked()).isFalse();
    }

    @Test
    void checkBudget_usageAtLimit_nullOnExceeded_defaultsToSkip() {
        // When onExceeded is null, the service defaults to NO_NEW_DISPATCH
        Budget budget = new Budget(null, Map.of(WORKER_TYPE_BE, 50000L));
        stubCurrentUsage(PLAN_ID, WORKER_TYPE_BE, 50000L);

        BudgetDecision decision = service.checkBudget(PLAN_ID, WORKER_TYPE_BE, budget);

        assertThat(decision.action()).isEqualTo(Action.SKIP);
    }

    // ── recordUsage ──────────────────────────────────────────────────────────

    @Test
    void recordUsage_existingRow_incrementsTokens() {
        when(usageRepository.incrementTokensUsed(PLAN_ID, WORKER_TYPE_BE, 1500L)).thenReturn(1);

        service.recordUsage(PLAN_ID, WORKER_TYPE_BE, 1500L);

        verify(usageRepository).incrementTokensUsed(PLAN_ID, WORKER_TYPE_BE, 1500L);
        verify(usageRepository, never()).save(any());
    }

    @Test
    void recordUsage_noExistingRow_createsRowThenIncrements() {
        // First incrementTokensUsed returns 0 (no row), second returns 1
        when(usageRepository.incrementTokensUsed(PLAN_ID, WORKER_TYPE_BE, 2000L))
                .thenReturn(0)
                .thenReturn(1);
        when(usageRepository.save(any(PlanTokenUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordUsage(PLAN_ID, WORKER_TYPE_BE, 2000L);

        verify(usageRepository).save(any(PlanTokenUsage.class));
        verify(usageRepository, times(2)).incrementTokensUsed(PLAN_ID, WORKER_TYPE_BE, 2000L);
    }

    @Test
    void recordUsage_zeroTokens_doesNothing() {
        service.recordUsage(PLAN_ID, WORKER_TYPE_BE, 0);

        verifyNoInteractions(usageRepository);
    }

    @Test
    void recordUsage_negativeTokens_doesNothing() {
        service.recordUsage(PLAN_ID, WORKER_TYPE_BE, -100L);

        verifyNoInteractions(usageRepository);
    }

    // ── currentUsage ─────────────────────────────────────────────────────────

    @Test
    void currentUsage_noRowExists_returnsZero() {
        when(usageRepository.findByPlanIdAndWorkerType(PLAN_ID, WORKER_TYPE_BE))
                .thenReturn(Optional.empty());

        long usage = service.currentUsage(PLAN_ID, WORKER_TYPE_BE);

        assertThat(usage).isZero();
    }

    @Test
    void currentUsage_rowExists_returnsAccumulatedTokens() {
        PlanTokenUsage entity = new PlanTokenUsage(PLAN_ID, WORKER_TYPE_BE);
        // PlanTokenUsage starts at 0, but we stub the find to return it
        when(usageRepository.findByPlanIdAndWorkerType(PLAN_ID, WORKER_TYPE_BE))
                .thenReturn(Optional.of(entity));

        long usage = service.currentUsage(PLAN_ID, WORKER_TYPE_BE);

        assertThat(usage).isZero(); // freshly created entity has 0 tokens
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private void stubCurrentUsage(UUID planId, String workerType, long tokens) {
        PlanTokenUsage entity = mock(PlanTokenUsage.class);
        when(entity.getTokensUsed()).thenReturn(tokens);
        when(usageRepository.findByPlanIdAndWorkerType(planId, workerType))
                .thenReturn(Optional.of(entity));
    }
}
