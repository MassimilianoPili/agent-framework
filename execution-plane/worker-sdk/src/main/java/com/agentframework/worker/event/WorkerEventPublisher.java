package com.agentframework.worker.event;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * G6: Publishes real-time worker events (tool calls, token updates) to the
 * {@code agent-events} Redis stream for SSE relay by the orchestrator.
 *
 * <p>Events are fire-and-forget — failures are logged but never block
 * tool execution or task processing.</p>
 */
public class WorkerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkerEventPublisher.class);

    private final MessageSender sender;
    private final String eventsTopic;

    public WorkerEventPublisher(MessageSender sender, String eventsTopic) {
        this.sender = sender;
        this.eventsTopic = eventsTopic;
    }

    public void publishToolCallStart(UUID planId, String taskKey, String toolName, String inputPreview) {
        publish("TOOL_CALL_START", planId, taskKey, Map.of(
            "toolName", toolName,
            "inputPreview", truncate(inputPreview, 200)));
    }

    public void publishToolCallEnd(UUID planId, String taskKey, String toolName,
                                    boolean success, long durationMs) {
        publish("TOOL_CALL_END", planId, taskKey, Map.of(
            "toolName", toolName,
            "success", String.valueOf(success),
            "durationMs", String.valueOf(durationMs)));
    }

    public void publishTokenUpdate(UUID planId, String taskKey,
                                    long promptTokens, long completionTokens, long totalTokens) {
        publish("TOKEN_UPDATE", planId, taskKey, Map.of(
            "promptTokens", String.valueOf(promptTokens),
            "completionTokens", String.valueOf(completionTokens),
            "totalTokens", String.valueOf(totalTokens)));
    }

    private void publish(String eventType, UUID planId, String taskKey, Map<String, String> extra) {
        try {
            // Build a compact JSON payload manually (no Jackson dependency needed)
            StringBuilder json = new StringBuilder(256);
            json.append("{\"eventType\":\"").append(eventType).append("\"");
            if (planId != null) json.append(",\"planId\":\"").append(planId).append("\"");
            if (taskKey != null) json.append(",\"taskKey\":\"").append(escapeJson(taskKey)).append("\"");
            json.append(",\"occurredAt\":\"").append(Instant.now()).append("\"");
            for (Map.Entry<String, String> e : extra.entrySet()) {
                json.append(",\"").append(e.getKey()).append("\":\"").append(escapeJson(e.getValue())).append("\"");
            }
            json.append("}");

            sender.send(new MessageEnvelope(
                UUID.randomUUID().toString(),
                eventsTopic,
                json.toString(),
                Map.of("eventType", eventType,
                       "planId", planId != null ? planId.toString() : "")));

        } catch (Exception e) {
            log.debug("Failed to publish worker event {}: {}", eventType, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
    }
}
