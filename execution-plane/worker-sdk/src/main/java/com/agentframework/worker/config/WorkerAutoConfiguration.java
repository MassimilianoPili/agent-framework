package com.agentframework.worker.config;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.MessageSender;
import com.agentframework.messaging.TaskLockService;
import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.context.SkillLoader;
import com.agentframework.worker.event.WorkerEventPublisher;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.agentframework.worker.messaging.WorkerTaskConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Optional;

/**
 * Auto-configuration for the worker-sdk (single-worker-per-JVM mode).
 *
 * <p>Follows the same pattern as DevOpsToolsAutoConfiguration:
 * <ul>
 *   <li>{@code @AutoConfiguration} (not {@code @Configuration})</li>
 *   <li>{@code @Import} to register SDK components</li>
 *   <li>{@code @ConditionalOnProperty} gate</li>
 * </ul>
 *
 * <p>Worker applications only need to:
 * <ol>
 *   <li>Declare a bean extending AbstractWorker</li>
 *   <li>Set properties: {@code agent.worker.*} in application.yml</li>
 *   <li>Add MCP tool starters to their pom.xml</li>
 * </ol>
 *
 * <p><strong>Disabled in consolidated JVM mode</strong> (#29 Phase 1b):
 * When {@code InProcessMessageBroker} is present (= in-process messaging),
 * this auto-configuration is skipped. The multi-worker registration is
 * handled by {@code InProcessWorkerAutoConfiguration} instead, which supports
 * multiple {@code AbstractWorker} beans on the same classpath.
 *
 * <p>MessageSender and MessageListenerContainer beans are provided
 * by the active messaging provider (JMS, Redis, or Service Bus).
 */
@AutoConfiguration
@ConditionalOnProperty(name = "agent.worker.task-topic")
@ConditionalOnMissingBean(type = "com.agentframework.messaging.inprocess.InProcessMessageBroker")
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
            WorkerProperties props,
            Optional<TaskLockService> taskLockService) {
        return new WorkerTaskConsumer(listenerContainer, worker, objectMapper, props, taskLockService);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerEventPublisher workerEventPublisher(
            MessageSender sender,
            WorkerProperties props) {
        return new WorkerEventPublisher(sender, props.getEventsTopic());
    }

    @Bean
    static CompactingToolCallingManagerPostProcessor compactingToolCallingManagerPostProcessor() {
        return new CompactingToolCallingManagerPostProcessor();
    }
}
