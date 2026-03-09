package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.budget.TokenLedger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Token ledger view for a plan: balance, totals, efficiency, and entry history (#33).
 */
public record TokenLedgerResponse(
    UUID planId,
    long balance,
    long totalDebits,
    long totalCredits,
    double efficiency,
    List<LedgerEntry> entries
) {

    public record LedgerEntry(
        String entryType,
        long amount,
        long balanceAfter,
        String taskKey,
        String workerType,
        String description,
        Instant createdAt
    ) {
        public static LedgerEntry from(TokenLedger entry) {
            return new LedgerEntry(
                    entry.getEntryType().name(),
                    entry.getAmount(),
                    entry.getBalanceAfter(),
                    entry.getTaskKey(),
                    entry.getWorkerType(),
                    entry.getDescription(),
                    entry.getCreatedAt());
        }
    }
}
