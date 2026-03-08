package com.agentframework.messaging.hybrid;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.MessageSender;
import com.agentframework.messaging.inprocess.InProcessMessageBroker;
import com.agentframework.messaging.inprocess.InProcessMessageListenerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration for hybrid messaging mode (#29 Phase 2).
 *
 * <p>Activated when {@code messaging.provider=hybrid}. Creates:
 * <ul>
 *   <li>{@link InProcessMessageBroker} — for in-process manager workers</li>
 *   <li>{@link HybridMessageSender} — routes tasks to in-process or remote based on workerType</li>
 *   <li>{@link InProcessMessageListenerContainer} — for result consumption (unified path)</li>
 *   <li>{@link RemoteWorkerClient} — HTTP client for remote dispatch/cancel</li>
 *   <li>{@link RemoteWorkerHealthMonitor} — periodic health checks on remote workers</li>
 * </ul>
 *
 * <p>All results (from both in-process and remote workers) flow through the
 * {@link InProcessMessageBroker}. Remote workers POST results to the orchestrator's
 * {@code /internal/results} endpoint, which injects them into the broker.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "messaging.provider", havingValue = "hybrid")
@EnableConfigurationProperties(HybridMessagingProperties.class)
@EnableScheduling
public class HybridMessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HybridMessagingAutoConfiguration.class);

    @Bean
    public InProcessMessageBroker inProcessMessageBroker() {
        return new InProcessMessageBroker();
    }

    @Bean
    public RemoteWorkerClient remoteWorkerClient(HybridMessagingProperties properties) {
        log.info("Hybrid messaging: configuring remote worker client for types {}",
                properties.getRemoteTypes());
        return new RemoteWorkerClient(properties);
    }

    @Bean
    @Primary
    public MessageSender messageSender(InProcessMessageBroker broker,
                                        RemoteWorkerClient client,
                                        HybridMessagingProperties properties) {
        log.info("Hybrid messaging: remote types={}, in-process=all others",
                properties.getRemoteTypes());
        return new HybridMessageSender(broker, client, properties);
    }

    @Bean
    public MessageListenerContainer messageListenerContainer(InProcessMessageBroker broker) {
        return new InProcessMessageListenerContainer(broker);
    }

    @Bean
    public RemoteWorkerHealthMonitor remoteWorkerHealthMonitor(RemoteWorkerClient client,
                                                                HybridMessagingProperties properties) {
        return new RemoteWorkerHealthMonitor(client, properties);
    }
}
