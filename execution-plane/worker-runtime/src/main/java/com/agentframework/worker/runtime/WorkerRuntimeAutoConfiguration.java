package com.agentframework.worker.runtime;

import com.agentframework.messaging.MessageSender;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.config.CompactingToolCallingManagerPostProcessor;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.context.SkillLoader;
import com.agentframework.worker.event.WorkerEventPublisher;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for the standalone worker-runtime (Phase 2 of #29).
 *
 * <p>Active when {@code worker-runtime.orchestrator-url} is set, indicating this is
 * a remote worker JVM that receives tasks via REST and sends results via HTTP callback.
 *
 * <p>Key differences from other deployment modes:
 * <ul>
 *   <li>No {@code MessageListenerContainer} — tasks arrive via {@link WorkerRuntimeController}</li>
 *   <li>No {@code WorkerTaskConsumer} — dispatch is handled by {@link LocalTaskDispatcher}</li>
 *   <li>{@link MessageSender} posts results to the orchestrator via HTTP callback
 *       instead of going through a message broker</li>
 * </ul>
 *
 * @see com.agentframework.worker.config.WorkerAutoConfiguration
 * @see com.agentframework.messaging.inprocess.InProcessWorkerAutoConfiguration
 */
@AutoConfiguration
@ConditionalOnProperty(name = "worker-runtime.orchestrator-url")
@Import({SkillLoader.class, AgentContextBuilder.class, WorkerChatClientFactory.class})
@ComponentScan(basePackages = "com.agentframework.workers")
public class WorkerRuntimeAutoConfiguration {

    private static final String RESULTS_TOPIC = "agent-results";
    private static final String EVENTS_TOPIC = "agent-events";

    @Bean
    @ConditionalOnMissingBean
    public MessageSender messageSender(
            @Value("${worker-runtime.orchestrator-url}") String orchestratorUrl) {
        return new ResultCallbackMessageSender(orchestratorUrl);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerResultProducer workerResultProducer(
            MessageSender sender,
            ObjectMapper objectMapper) {
        return new WorkerResultProducer(sender, objectMapper, RESULTS_TOPIC, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerEventPublisher workerEventPublisher(MessageSender sender) {
        return new WorkerEventPublisher(sender, EVENTS_TOPIC);
    }

    @Bean
    @ConditionalOnMissingBean
    static CompactingToolCallingManagerPostProcessor compactingToolCallingManagerPostProcessor() {
        return new CompactingToolCallingManagerPostProcessor();
    }
}
