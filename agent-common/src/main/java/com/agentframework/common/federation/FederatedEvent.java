package com.agentframework.common.federation;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight transport DTO for plan events in a federated cluster.
 *
 * <p>This record mirrors the essential fields of the orchestrator's {@code PlanEvent}
 * entity without depending on it. Federation providers convert internal events to
 * this form before broadcast, and convert back on receive.</p>
 *
 * <p>The {@code eventHash} and {@code previousHash} fields form a hash chain
 * (established by feature #30) that enables efficient Merkle-tree reconciliation
 * between peers: compare roots, exchange only divergent subtrees.</p>
 *
 * @param eventId        unique event identifier
 * @param planId         the plan this event belongs to
 * @param itemId         the plan item (null for plan-level events)
 * @param eventType      event type constant (e.g. "TASK_DISPATCHED", "TASK_COMPLETED")
 * @param payloadJson    opaque JSON payload; schema depends on {@code eventType}
 * @param occurredAt     when the event occurred on the originating server
 * @param sequenceNumber per-plan monotonic sequence for causal ordering
 * @param eventHash      SHA-256 hash of this event (tamper-proof chain)
 * @param previousHash   SHA-256 hash of the preceding event in the chain
 */
public record FederatedEvent(
        UUID eventId,
        UUID planId,
        UUID itemId,
        String eventType,
        String payloadJson,
        Instant occurredAt,
        long sequenceNumber,
        String eventHash,
        String previousHash
) {}
