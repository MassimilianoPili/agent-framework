package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.config.TokenLedgerProperties;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.metrics.OrchestratorMetrics;
import com.agentframework.orchestrator.repository.TokenLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TokenLedgerService} — double-entry token accounting (#33).
 */
@ExtendWith(MockitoExtension.class)
class TokenLedgerServiceTest {

    @Mock private TokenLedgerRepository repository;
    @Mock private OrchestratorMetrics metrics;
    @Mock private ApplicationEventPublisher eventPublisher;

    private TokenLedgerService service;

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TokenLedgerProperties properties = new TokenLedgerProperties(0.15);
        service = new TokenLedgerService(repository, metrics, eventPublisher, properties);
    }

    // ── Debit tests ──────────────────────────────────────────────────────────

    @Test
    void debit_zeroTokens_noEntry() {
        service.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 0, "test");
        verify(repository, never()).save(any());
    }

    @Test
    void debit_negativeTokens_noEntry() {
        service.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", -100, "test");
        verify(repository, never()).save(any());
    }

    @Test
    void debit_positiveTokens_savesEntry() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.empty());
        lenient().when(repository.countDebits(PLAN_ID)).thenReturn(1L);

        service.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 5000, "Task completed: be-java");

        ArgumentCaptor<TokenLedger> captor = ArgumentCaptor.forClass(TokenLedger.class);
        verify(repository).save(captor.capture());

        TokenLedger entry = captor.getValue();
        assertEquals(PLAN_ID, entry.getPlanId());
        assertEquals(ITEM_ID, entry.getItemId());
        assertEquals("BE-001", entry.getTaskKey());
        assertEquals("BE", entry.getWorkerType());
        assertEquals(TokenLedger.EntryType.DEBIT, entry.getEntryType());
        assertEquals(5000, entry.getAmount());
        assertEquals(-5000, entry.getBalanceAfter());
        assertEquals("Task completed: be-java", entry.getDescription());
    }

    @Test
    void debit_secondEntry_runningBalance() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(-5000L));
        lenient().when(repository.countDebits(PLAN_ID)).thenReturn(2L);

        service.debit(PLAN_ID, ITEM_ID, "FE-001", "FE", 3000, "second task");

        ArgumentCaptor<TokenLedger> captor = ArgumentCaptor.forClass(TokenLedger.class);
        verify(repository).save(captor.capture());

        assertEquals(-8000, captor.getValue().getBalanceAfter());
    }

    // ── Credit tests ─────────────────────────────────────────────────────────

    @Test
    void credit_infrastructureWorker_noEntry() {
        service.credit(PLAN_ID, ITEM_ID, "CM-001", "CONTEXT_MANAGER", 5000, 0.8);
        verify(repository, never()).save(any());
    }

    @Test
    void credit_reviewWorker_noEntry() {
        service.credit(PLAN_ID, ITEM_ID, "RV-001", "REVIEW", 5000, 0.9);
        verify(repository, never()).save(any());
    }

    @Test
    void credit_negativeReward_noEntry() {
        service.credit(PLAN_ID, ITEM_ID, "BE-001", "BE", 5000, -0.5);
        verify(repository, never()).save(any());
    }

    @Test
    void credit_zeroReward_noEntry() {
        service.credit(PLAN_ID, ITEM_ID, "BE-001", "BE", 5000, 0.0);
        verify(repository, never()).save(any());
    }

    @Test
    void credit_positiveReward_savesEntry() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(-5000L));

        service.credit(PLAN_ID, ITEM_ID, "BE-001", "BE", 5000, 0.8);

        ArgumentCaptor<TokenLedger> captor = ArgumentCaptor.forClass(TokenLedger.class);
        verify(repository).save(captor.capture());

        TokenLedger entry = captor.getValue();
        assertEquals(TokenLedger.EntryType.CREDIT, entry.getEntryType());
        assertEquals(4000, entry.getAmount()); // Math.round(5000 * 0.8)
        assertEquals(-1000, entry.getBalanceAfter()); // -5000 + 4000
        assertTrue(entry.getDescription().contains("0.800"));
    }

    @Test
    void credit_fractionalRounding() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(0L));

        // 3333 * 0.333 = 1109.889 → rounds to 1110
        service.credit(PLAN_ID, ITEM_ID, "AI-001", "AI_TASK", 3333, 0.333);

        ArgumentCaptor<TokenLedger> captor = ArgumentCaptor.forClass(TokenLedger.class);
        verify(repository).save(captor.capture());

        assertEquals(1110, captor.getValue().getAmount());
    }

    // ── Efficiency tests ─────────────────────────────────────────────────────

    @Test
    void computeEfficiency_noDebits_returnsZero() {
        when(repository.sumDebits(PLAN_ID)).thenReturn(0L);

        assertEquals(0.0, service.computeEfficiency(PLAN_ID));
    }

    @Test
    void computeEfficiency_withEntries_returnsRatio() {
        when(repository.sumDebits(PLAN_ID)).thenReturn(10000L);
        when(repository.sumCredits(PLAN_ID)).thenReturn(7500L);

        assertEquals(0.75, service.computeEfficiency(PLAN_ID), 0.001);
    }

    // ── Query tests ──────────────────────────────────────────────────────────

    @Test
    void getLedger_returnsSortedEntries() {
        List<TokenLedger> expected = List.of();
        when(repository.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(expected);

        assertSame(expected, service.getLedger(PLAN_ID));
    }

    @Test
    void currentBalance_noEntries_returnsZero() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.empty());

        assertEquals(0L, service.currentBalance(PLAN_ID));
    }

    // ── Per-workerType efficiency (#33 Phase 2) ─────────────────────────────

    @Test
    void computeEfficiencyByWorkerType_multipleWorkers_returnsPerTypeBreakdown() {
        when(repository.sumDebitsByWorkerType(PLAN_ID)).thenReturn(List.of(
                new Object[]{"BE", 8000L},
                new Object[]{"FE", 4000L}
        ));
        when(repository.sumCreditsByWorkerType(PLAN_ID)).thenReturn(List.of(
                new Object[]{"BE", 6000L},
                new Object[]{"FE", 1000L}
        ));

        Map<String, TokenLedgerService.WorkerTypeEfficiency> result =
                service.computeEfficiencyByWorkerType(PLAN_ID);

        assertEquals(2, result.size());

        TokenLedgerService.WorkerTypeEfficiency be = result.get("BE");
        assertEquals(8000, be.debits());
        assertEquals(6000, be.credits());
        assertEquals(0.75, be.efficiency(), 0.001);

        TokenLedgerService.WorkerTypeEfficiency fe = result.get("FE");
        assertEquals(4000, fe.debits());
        assertEquals(1000, fe.credits());
        assertEquals(0.25, fe.efficiency(), 0.001);
    }

    @Test
    void computeEfficiencyByWorkerType_noEntries_returnsEmptyMap() {
        when(repository.sumDebitsByWorkerType(PLAN_ID)).thenReturn(List.of());
        when(repository.sumCreditsByWorkerType(PLAN_ID)).thenReturn(List.of());

        Map<String, TokenLedgerService.WorkerTypeEfficiency> result =
                service.computeEfficiencyByWorkerType(PLAN_ID);

        assertTrue(result.isEmpty());
    }

    // ── Burn rate (#33 Phase 4) ─────────────────────────────────────────────

    @Test
    void computeBurnRate_twoEntries_calculatesCorrectly() throws Exception {
        Instant t0 = Instant.parse("2026-03-09T10:00:00Z");
        Instant t1 = t0.plus(10, ChronoUnit.MINUTES);

        TokenLedger d1 = TokenLedger.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 3000, -3000, "first");
        TokenLedger d2 = TokenLedger.debit(PLAN_ID, ITEM_ID, "FE-001", "FE", 7000, -10000, "second");
        setCreatedAt(d1, t0);
        setCreatedAt(d2, t1);

        when(repository.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(List.of(d1, d2));

        OptionalDouble rate = service.computeBurnRate(PLAN_ID);

        assertTrue(rate.isPresent());
        // (3000 + 7000) / 10 min = 1000 tokens/min
        assertEquals(1000.0, rate.getAsDouble(), 0.01);
    }

    @Test
    void computeBurnRate_singleEntry_returnsEmpty() {
        TokenLedger d1 = TokenLedger.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 3000, -3000, "only");
        when(repository.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenReturn(List.of(d1));

        OptionalDouble rate = service.computeBurnRate(PLAN_ID);

        assertTrue(rate.isEmpty());
    }

    // ── Low-efficiency alert (#33 Phase 3) ──────────────────────────────────

    @Test
    void debit_belowEfficiencyThreshold_publishesAlert() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(0L));
        when(repository.countDebits(PLAN_ID)).thenReturn(5L);
        when(repository.sumDebits(PLAN_ID)).thenReturn(10000L);
        when(repository.sumCredits(PLAN_ID)).thenReturn(500L); // efficiency = 0.05 < 0.15

        service.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 2000, "triggers alert");

        verify(eventPublisher).publishEvent(any(SpringPlanEvent.class));
    }

    @Test
    void debit_aboveEfficiencyThreshold_noAlert() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(0L));
        when(repository.countDebits(PLAN_ID)).thenReturn(5L);
        when(repository.sumDebits(PLAN_ID)).thenReturn(10000L);
        when(repository.sumCredits(PLAN_ID)).thenReturn(5000L); // efficiency = 0.5 > 0.15

        service.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 2000, "no alert");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void debit_alertNotRepeatedForSamePlan() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(0L));
        when(repository.countDebits(PLAN_ID)).thenReturn(5L);
        when(repository.sumDebits(PLAN_ID)).thenReturn(10000L);
        when(repository.sumCredits(PLAN_ID)).thenReturn(500L); // efficiency = 0.05 < 0.15

        service.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 2000, "first debit");
        service.debit(PLAN_ID, ITEM_ID, "BE-002", "BE", 2000, "second debit");

        // Alert published only once (guard: alertedPlans Set)
        verify(eventPublisher, times(1)).publishEvent(any(SpringPlanEvent.class));
    }

    // ── Prometheus metrics (#33 Phase 1) ────────────────────────────────────

    @Test
    void debit_emitsPrometheusMetric() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.empty());
        lenient().when(repository.countDebits(PLAN_ID)).thenReturn(1L);

        service.debit(PLAN_ID, ITEM_ID, "BE-001", "BE", 5000, "test");

        verify(metrics).recordLedgerDebit("BE", 5000);
    }

    @Test
    void credit_emitsPrometheusMetric() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(0L));

        service.credit(PLAN_ID, ITEM_ID, "BE-001", "BE", 5000, 0.8);

        verify(metrics).recordLedgerCredit("BE", 4000, "standard");
    }

    @Test
    void creditShapley_emitsPrometheusMetric() {
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(0L));

        service.creditShapley(PLAN_ID, ITEM_ID, "CM-001", "CONTEXT_MANAGER", 0.05, 100000);

        // 0.05 * 100000 = 5000
        verify(metrics).recordLedgerCredit("CONTEXT_MANAGER", 5000, "shapley");
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static void setCreatedAt(TokenLedger entry, Instant instant) throws Exception {
        Field field = TokenLedger.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entry, instant);
    }
}
