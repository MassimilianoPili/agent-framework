package com.agentframework.orchestrator.messaging;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import com.agentframework.orchestrator.messaging.dto.AgentTask;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AgentTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskProducer.class);

    private final MessageSender sender;
    private final ObjectMapper objectMapper;
    private final WorkerProfileRegistry profileRegistry;

    public AgentTaskProducer(MessageSender sender, ObjectMapper objectMapper,
                             WorkerProfileRegistry profileRegistry) {
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Serializes an AgentTask to JSON and sends it to the topic resolved
     * from the worker profile registry (workerType + workerProfile -> topic).
     *
     * Message properties set for observability and routing:
     * - messageId: itemId (deduplication key)
     * - taskKey: human-readable identifier
     * - workerType: semantic type (BE, FE, etc.)
     * - workerProfile: concrete stack profile (be-java, be-go, etc.) if present
     * - planId: plan identifier for correlation
     */
    public void dispatch(AgentTask task) {
        try {
            String topic = profileRegistry.resolveTopic(task.workerType(), task.workerProfile());
            String json = objectMapper.writeValueAsString(task);

            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("taskKey", task.taskKey());
            properties.put("workerType", task.workerType().name());
            properties.put("planId", task.planId().toString());
            if (task.workerProfile() != null) {
                properties.put("workerProfile", task.workerProfile());
            }

            sender.send(new MessageEnvelope(
                    task.itemId().toString(),
                    topic,
                    json,
                    properties
            ));

            log.info("Dispatched task {} to topic {} [profile={}] (plan={})",
                     task.taskKey(), topic, task.workerProfile(), task.planId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to dispatch AgentTask: " + task.taskKey(), e);
        }
    }
}
