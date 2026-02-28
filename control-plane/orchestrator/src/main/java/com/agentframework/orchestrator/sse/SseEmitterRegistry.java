package com.agentframework.orchestrator.sse;

import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEvent;
import com.agentframework.orchestrator.eventsourcing.PlanEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry that manages SSE connections and broadcasts plan events to subscribed clients.
 *
 * <p>Thread-safety: uses ConcurrentHashMap + CopyOnWriteArrayList since subscribe/broadcast
 * can be called concurrently from different HTTP threads.</p>
 *
 * <p>Emitter lifecycle: on completion, timeout, or error the emitter is automatically removed
 * from the registry to prevent memory leaks from stale references.</p>
 *
 * <p>Late-join replay: clients connecting after the plan started receive all past events
 * from {@link PlanEventStore} before being added to the live broadcast list.
 * Event IDs match {@link PlanEvent#getSequenceNumber()} so clients can resume via
 * the {@code Last-Event-ID} header if they reconnect after a disconnect.</p>
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** SSE timeout: 5 minutes. Client should reconnect via Last-Event-ID if needed. */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final PlanEventStore eventStore;

    public SseEmitterRegistry(ObjectMapper objectMapper, PlanEventStore eventStore) {
        this.objectMapper = objectMapper;
        this.eventStore = eventStore;
    }

    /**
     * Creates and registers a new SSE emitter for the given plan.
     *
     * <p>Late-join replay: all past events for the plan are sent immediately after
     * registration, so clients connecting after the plan has started do not miss any
     * state transitions. The client receives the full history in sequence order,
     * then continues receiving live events.</p>
     *
     * <p>The emitter is automatically removed on completion, timeout, or error.</p>
     */
    public SseEmitter subscribe(UUID planId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Late-join replay: send all past events before registering for live ones.
        // This must happen BEFORE adding the emitter to the live list to avoid
        // duplicate delivery if an event fires concurrently during replay.
        List<PlanEvent> pastEvents = eventStore.findByPlanId(planId);
        for (PlanEvent pastEvent : pastEvents) {
            try {
                emitter.send(SseEmitter.event()
                        .name(pastEvent.getEventType())
                        .id(String.valueOf(pastEvent.getSequenceNumber()))
                        .data(pastEvent.getPayload(), MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                // Client disconnected during replay — abort silently
                log.debug("SSE client disconnected during replay for plan {} (seq={}): {}",
                          planId, pastEvent.getSequenceNumber(), e.getMessage());
                return emitter;
            }
        }

        List<SseEmitter> planEmitters = emitters
                .computeIfAbsent(planId, k -> new CopyOnWriteArrayList<>());
        planEmitters.add(emitter);

        Runnable cleanup = () -> {
            planEmitters.remove(emitter);
            if (planEmitters.isEmpty()) {
                emitters.remove(planId, planEmitters);
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        log.debug("SSE client subscribed to plan {} ({} total for this plan)",
                  planId, planEmitters.size());
        return emitter;
    }

    /**
     * Broadcasts a plan event to all connected clients for that plan.
     * Removes emitters that fail to send (client disconnected).
     */
    @EventListener
    public void onPlanEvent(SpringPlanEvent event) {
        List<SseEmitter> planEmitters = emitters.get(event.planId());
        if (planEmitters == null || planEmitters.isEmpty()) {
            return;
        }

        String data;
        try {
            data = objectMapper.writeValueAsString(Map.of(
                    "eventType",    event.eventType(),
                    "planId",       event.planId().toString(),
                    "itemId",       event.itemId() != null ? event.itemId().toString() : null,
                    "taskKey",      event.taskKey(),
                    "workerProfile", event.workerProfile(),
                    "success",      event.success(),
                    "durationMs",   event.durationMs(),
                    "occurredAt",   event.occurredAt().toString()
            ));
        } catch (Exception e) {
            log.warn("Failed to serialize SSE event {}: {}", event.eventType(), e.getMessage());
            return;
        }

        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
        for (SseEmitter emitter : planEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.eventType())
                        .data(data));
            } catch (IOException ex) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            planEmitters.removeAll(deadEmitters);
            log.debug("Removed {} dead SSE emitter(s) for plan {}", deadEmitters.size(), event.planId());
        }
    }
}
