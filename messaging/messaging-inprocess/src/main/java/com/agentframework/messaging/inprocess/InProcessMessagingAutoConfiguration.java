package com.agentframework.messaging.inprocess;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.MessageSender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for the in-process messaging provider.
 *
 * <p>Activated when {@code messaging.provider=in-process}. Provides:
 * <ul>
 *   <li>{@link InProcessMessageBroker} — shared singleton, holds the handler registry and running-task map</li>
 *   <li>{@link InProcessMessageSender} — dispatches envelopes to the broker (replaces Redis XADD)</li>
 *   <li>{@link InProcessMessageListenerContainer} — registers handlers with the broker (replaces XREAD loop)</li>
 * </ul>
 *
 * <p>Redis is still required for cache (DB 4) and RAG (DB 5) — only the messaging streams (DB 3) are replaced.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "messaging.provider", havingValue = "in-process")
public class InProcessMessagingAutoConfiguration {

    @Bean
    public InProcessMessageBroker inProcessMessageBroker() {
        return new InProcessMessageBroker();
    }

    @Bean
    @Primary
    public MessageSender messageSender(InProcessMessageBroker broker) {
        return new InProcessMessageSender(broker);
    }

    @Bean
    public MessageListenerContainer messageListenerContainer(InProcessMessageBroker broker) {
        return new InProcessMessageListenerContainer(broker);
    }
}
