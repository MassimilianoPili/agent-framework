package com.agentframework.orchestrator.messaging;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * G6: Consumes worker events from the {@code agent-events} Redis stream and
 * translates them into {@link SpringPlanEvent}s for SSE broadcast.
 *
 * <p>Events are fire-and-forget: if deserialization fails, the message is ACKed
 * and dropped (no redelivery for transient worker telemetry).</p>
 */
@Component
public class WorkerEventListener {

    private static final Logger log = LoggerFactory.getLogger(WorkerEventListener.class);

    private final MessageListenerContainer listenerContainer;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${messaging.agent-events.topic:agent-events}")
    private String eventsTopic;

    @Value("${messaging.agent-events.subscription:orchestrator-events-group}")
    private String eventsSubscription;

    public WorkerEventListener(MessageListenerContainer listenerContainer,
                               ApplicationEventPublisher eventPublisher,
                               ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        listenerContainer.subscribe(eventsTopic, eventsSubscription, (body, ack) -> {
            try {
                handleEvent(body);
            } catch (Exception e) {
                log.debug("Failed to process worker event: {}", e.getMessage());
            } finally {
                ack.complete(); // always ACK — telemetry is best-effort
            }
        });
        log.info("WorkerEventListener registered on stream '{}'", eventsTopic);
    }

    private void handleEvent(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        String eventType = node.path("eventType").asText();
        String planIdStr = node.path("planId").asText(null);
        String taskKey = node.path("taskKey").asText(null);

        UUID planId = (planIdStr != null && !planIdStr.isEmpty()) ? UUID.fromString(planIdStr) : null;

        // Build extraJson from all fields except eventType, planId, taskKey, occurredAt
        String extraJson = body; // pass the full event JSON as extra — dashboard can pick what it needs

        SpringPlanEvent event = new SpringPlanEvent(
            eventType, planId, null, taskKey, null,
            true, 0, Instant.now(), extraJson);

        eventPublisher.publishEvent(event);
    }
}
