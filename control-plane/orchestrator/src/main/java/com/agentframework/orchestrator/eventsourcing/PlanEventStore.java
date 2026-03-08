package com.agentframework.orchestrator.eventsourcing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only event store for plan state changes.
 *
 * <p>Used in the hybrid event-sourcing approach: the Plan/PlanItem JPA entities
 * remain the primary read model, while every state transition is also captured here
 * for audit and SSE late-join replay.</p>
 *
 * <p>Propagation.MANDATORY: this store must always be called within an existing
 * transaction (opened by OrchestrationService) to ensure atomicity between the
 * read-model update and the event append.</p>
 */
@Service
public class PlanEventStore {

    private static final Logger log = LoggerFactory.getLogger(PlanEventStore.class);

    private final PlanEventRepository repository;
    private final ObjectMapper objectMapper;

    public PlanEventStore(PlanEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends an event to the log.
     *
     * @param planId    the plan this event belongs to
     * @param itemId    null for plan-level events
     * @param eventType event type string (use {@link com.agentframework.orchestrator.event.SpringPlanEvent} constants)
     * @param payload   arbitrary object serialized to JSON; null produces "{}"
     * @return the persisted event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PlanEvent append(UUID planId, UUID itemId, String eventType, Object payload) {
        long seq = repository.nextSequence(planId);

        String payloadJson;
        try {
            payloadJson = payload != null ? objectMapper.writeValueAsString(payload) : "{}";
        } catch (Exception e) {
            log.warn("Failed to serialize event payload for {} (plan={}, item={}): {}",
                     eventType, planId, itemId, e.getMessage());
            payloadJson = "{}";
        }

        PlanEvent event = new PlanEvent(
                UUID.randomUUID(), planId, itemId,
                eventType, payloadJson,
                Instant.now(), seq);

        PlanEvent saved = repository.save(event);
        log.debug("Event appended: type={}, plan={}, item={}, seq={}",
                  eventType, planId, itemId, seq);
        return saved;
    }

    /** Returns all events for a plan in sequence order (for SSE late-join replay). */
    @Transactional(readOnly = true)
    public List<PlanEvent> findByPlanId(UUID planId) {
        return repository.findByPlanIdOrderBySequenceNumberAsc(planId);
    }

    /**
     * Returns events after {@code afterSeqNum} in sequence order.
     *
     * <p>Used for SSE resume: when a client reconnects with a {@code Last-Event-ID} header,
     * only the missed events are replayed instead of the full history.</p>
     *
     * @param planId      the plan to query
     * @param afterSeqNum exclusive lower bound (the last sequence number the client received)
     */
    @Transactional(readOnly = true)
    public List<PlanEvent> findByPlanIdAfter(UUID planId, long afterSeqNum) {
        return repository.findByPlanIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                planId, afterSeqNum);
    }
}
