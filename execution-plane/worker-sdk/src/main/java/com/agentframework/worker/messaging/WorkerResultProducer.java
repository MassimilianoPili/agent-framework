package com.agentframework.worker.messaging;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import com.agentframework.worker.dto.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Publishes AgentResult messages to the results topic.
 * The orchestrator's AgentResultConsumer picks these up.
 */
public class WorkerResultProducer {

    private static final Logger log = LoggerFactory.getLogger(WorkerResultProducer.class);

    private final MessageSender sender;
    private final ObjectMapper objectMapper;
    private final String resultsTopic;

    public WorkerResultProducer(MessageSender sender, ObjectMapper objectMapper, String resultsTopic) {
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.resultsTopic = resultsTopic;
    }

    public void publish(AgentResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);

            sender.send(new MessageEnvelope(
                    result.itemId().toString(),
                    resultsTopic,
                    json,
                    Map.of("planId", result.planId().toString(),
                           "taskKey", result.taskKey(),
                           "success", String.valueOf(result.success()))
            ));

            log.info("Published AgentResult for task {} (plan={}, success={})",
                     result.taskKey(), result.planId(), result.success());
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish AgentResult for task: " + result.taskKey(), e);
        }
    }
}
