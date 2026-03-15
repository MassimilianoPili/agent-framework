package com.agentframework.messaging.inprocess;

import com.agentframework.messaging.MessageSender;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.config.CompactingToolCallingManagerPostProcessor;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.context.SkillLoader;
import com.agentframework.worker.event.WorkerEventPublisher;
import com.agentframework.worker.messaging.WorkerResultProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for running multiple workers in a single JVM (Phase 1b of #29).
 *
 * <p>Active when an {@link InProcessMessageBroker} bean exists (both {@code in-process}
 * and {@code hybrid} messaging modes). This replaces {@code WorkerAutoConfiguration}
 * (which handles the single-worker case) with a multi-worker setup.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Component scan of {@code com.agentframework.workers} (via {@link InProcessWorkerScanConfig})</li>
 *   <li>Shared infrastructure beans: {@link SkillLoader}, {@link AgentContextBuilder},
 *       {@link WorkerChatClientFactory}</li>
 *   <li>Shared {@link WorkerResultProducer} and {@link WorkerEventPublisher}</li>
 *   <li>{@link CompactingToolCallingManagerPostProcessor} for tool call compaction</li>
 *   <li>Worker registration is handled by {@link InProcessWorkerRegistrar}</li>
 * </ul>
 *
 * <p>The corresponding {@code WorkerAutoConfiguration} is disabled via
 * {@code @ConditionalOnMissingBean(type = "...InProcessMessageBroker")} when this
 * auto-configuration is active.
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.agentframework.worker.context.SkillLoader")
@ConditionalOnBean(InProcessMessageBroker.class)
@Import({SkillLoader.class, AgentContextBuilder.class, WorkerChatClientFactory.class,
         InProcessWorkerScanConfig.class})
public class InProcessWorkerAutoConfiguration {

    private static final String RESULTS_TOPIC = "agent-results";
    private static final String EVENTS_TOPIC = "agent-events";

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
