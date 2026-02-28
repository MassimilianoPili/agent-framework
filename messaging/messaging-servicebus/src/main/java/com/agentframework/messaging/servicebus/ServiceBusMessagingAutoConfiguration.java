package com.agentframework.messaging.servicebus;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.MessageSender;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Azure Service Bus messaging provider.
 * Active only when messaging.provider=servicebus (NOT the default).
 */
@AutoConfiguration
@ConditionalOnProperty(name = "messaging.provider", havingValue = "servicebus")
@ConditionalOnClass(ServiceBusClientBuilder.class)
@EnableConfigurationProperties(ServiceBusMessagingProperties.class)
public class ServiceBusMessagingAutoConfiguration {

    @Bean
    public MessageSender messageSender(ServiceBusMessagingProperties properties) {
        return new ServiceBusMessageSender(properties.getConnectionString());
    }

    @Bean
    public MessageListenerContainer messageListenerContainer(ServiceBusMessagingProperties properties) {
        return new ServiceBusListenerContainer(
                properties.getConnectionString(),
                properties.getMaxConcurrentCalls());
    }
}
