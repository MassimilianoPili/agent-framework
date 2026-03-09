package com.agentframework.orchestrator.budget;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only double-entry ledger for per-plan token accounting (#33).
 *
 * <p>Each entry records either a DEBIT (tokens consumed) or CREDIT (value produced,
 * proportional to {@code aggregatedReward}). The running balance {@code balanceAfter}
 * is computed atomically at INSERT time, giving O(1) balance reads.</p>
 *
 * <p>Accounting invariant: {@code SUM(DEBIT) - SUM(CREDIT) = net_spend}.</p>
 */
@Entity
@Table(name = "token_ledger")
public class TokenLedger {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "task_key", length = 20)
    private String taskKey;

    @Column(name = "worker_type", nullable = false, length = 50)
    private String workerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 6)
    private EntryType entryType;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum EntryType { DEBIT, CREDIT }

    protected TokenLedger() {}

    private TokenLedger(UUID planId, UUID itemId, String taskKey, String workerType,
                        EntryType entryType, long amount, long balanceAfter, String description) {
        this.id = UUID.randomUUID();
        this.planId = planId;
        this.itemId = itemId;
        this.taskKey = taskKey;
        this.workerType = workerType;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public static TokenLedger debit(UUID planId, UUID itemId, String taskKey,
                                     String workerType, long amount, long balanceAfter,
                                     String description) {
        return new TokenLedger(planId, itemId, taskKey, workerType,
                EntryType.DEBIT, amount, balanceAfter, description);
    }

    public static TokenLedger credit(UUID planId, UUID itemId, String taskKey,
                                      String workerType, long amount, long balanceAfter,
                                      String description) {
        return new TokenLedger(planId, itemId, taskKey, workerType,
                EntryType.CREDIT, amount, balanceAfter, description);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public UUID getItemId() { return itemId; }
    public String getTaskKey() { return taskKey; }
    public String getWorkerType() { return workerType; }
    public EntryType getEntryType() { return entryType; }
    public long getAmount() { return amount; }
    public long getBalanceAfter() { return balanceAfter; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
