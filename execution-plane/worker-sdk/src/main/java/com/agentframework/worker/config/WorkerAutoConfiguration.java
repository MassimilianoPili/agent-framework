package com.agentframework.worker.config;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.MessageSender;
import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.context.SkillLoader;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.agentframework.worker.messaging.WorkerTaskConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for the worker-sdk.
 *
 * Follows the same pattern as DevOpsToolsAutoConfiguration:
 * - @AutoConfiguration (not @Configuration)
 * - @Import to register SDK components
 * - @ConditionalOnProperty gate
 *
 * Worker applications only need to:
 * 1. Declare a bean extending AbstractWorker
 * 2. Set properties: agent.worker.* in application.yml
 * 3. Add MCP tool starters to their pom.xml
 *
 * MessageSender and MessageListenerContainer beans are provided
 * by the active messaging provider (JMS, Redis, or Service Bus).
 */
@AutoConfiguration
@ConditionalOnProperty(name = "agent.worker.task-topic")
@EnableConfigurationProperties(WorkerProperties.class)
@Import({SkillLoader.class, AgentContextBuilder.class, WorkerChatClientFactory.class})
public class WorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkerResultProducer workerResultProducer(
            MessageSender sender,
            ObjectMapper objectMapper,
            WorkerProperties props) {
        return new WorkerResultProducer(sender, objectMapper, props.getResultsTopic());
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerTaskConsumer workerTaskConsumer(
            MessageListenerContainer listenerContainer,
            AbstractWorker worker,
            ObjectMapper objectMapper,
            WorkerProperties props) {
        return new WorkerTaskConsumer(listenerContainer, worker, objectMapper, props);
    }
}
