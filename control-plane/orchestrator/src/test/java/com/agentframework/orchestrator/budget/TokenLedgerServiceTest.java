package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.repository.TokenLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
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

    private TokenLedgerService service;

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TokenLedgerService(repository);
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
        // First debit left balance at -5000
        when(repository.findLatestBalance(PLAN_ID)).thenReturn(Optional.of(-5000L));

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
}
