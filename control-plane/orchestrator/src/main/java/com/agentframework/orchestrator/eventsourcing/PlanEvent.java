package com.agentframework.orchestrator.eventsourcing;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only event record for the plan event log.
 *
 * <p>Each meaningful state transition in the orchestrator is captured here.
 * Together with the Plan/PlanItem read model (hybrid approach), this table forms
 * the audit trail and enables SSE late-join replay.</p>
 *
 * <p>Design decisions:</p>
 * <ul>
 *   <li>No cascade delete — events are retained permanently for audit</li>
 *   <li>sequenceNumber is per-plan (not global) — sufficient for ordering per-plan streams</li>
 *   <li>payload is a freeform JSON blob — strongly typed by eventType convention</li>
 * </ul>
 */
@Entity
@Table(name = "plan_event",
       indexes = @Index(name = "idx_plan_event_plan_seq", columnList = "plan_id, sequence_number"),
       uniqueConstraints = @UniqueConstraint(name = "uq_plan_event_seq",
               columnNames = {"plan_id", "sequence_number"}))
public class PlanEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    /** Null for plan-level events (PLAN_STARTED, PLAN_COMPLETED, etc.). */
    @Column(name = "item_id", updatable = false)
    private UUID itemId;

    /** Event type string — mirrors {@link com.agentframework.orchestrator.event.SpringPlanEvent} constants. */
    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    /** JSON payload — schema depends on eventType. */
    @Column(name = "payload", columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Monotonically increasing per planId. Used for SSE late-join replay ordering. */
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    /** SHA-256 hash of (previousHash | eventType | payload | occurredAt). Forms tamper-proof chain (#30). */
    @Column(name = "event_hash", nullable = false, length = 64, updatable = false)
    private String eventHash = "";

    /** Hash of the previous event in the chain. Genesis events use "000...0" (64 zeros). */
    @Column(name = "previous_hash", nullable = false, length = 64, updatable = false)
    private String previousHash = "";

    protected PlanEvent() {}

    public PlanEvent(UUID id, UUID planId, UUID itemId, String eventType,
                     String payload, Instant occurredAt, long sequenceNumber) {
        this.id = id;
        this.planId = planId;
        this.itemId = itemId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.sequenceNumber = sequenceNumber;
    }

    /** Constructor with hash chain fields (#30). */
    public PlanEvent(UUID id, UUID planId, UUID itemId, String eventType,
                     String payload, Instant occurredAt, long sequenceNumber,
                     String eventHash, String previousHash) {
        this(id, planId, itemId, eventType, payload, occurredAt, sequenceNumber);
        this.eventHash = eventHash;
        this.previousHash = previousHash;
    }

    public UUID getId()             { return id; }
    public UUID getPlanId()         { return planId; }
    public UUID getItemId()         { return itemId; }
    public String getEventType()    { return eventType; }
    public String getPayload()      { return payload; }
    public Instant getOccurredAt()  { return occurredAt; }
    public long getSequenceNumber() { return sequenceNumber; }
    public String getEventHash()    { return eventHash; }
    public String getPreviousHash() { return previousHash; }
}
