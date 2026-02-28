package com.agentframework.messaging.jms;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.MessageSender;
import jakarta.jms.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.core.JmsTemplate;

/**
 * Auto-configuration for JMS messaging provider.
 * This is the DEFAULT provider — active when messaging.provider is not set.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "messaging.provider", havingValue = "jms", matchIfMissing = true)
@ConditionalOnClass(ConnectionFactory.class)
@EnableConfigurationProperties(JmsMessagingProperties.class)
public class JmsMessagingAutoConfiguration {

    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory,
                                   JmsMessagingProperties properties) {
        var template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(properties.isPubSubDomain());
        template.setDeliveryPersistent(true);
        return template;
    }

    @Bean
    public MessageSender messageSender(JmsTemplate jmsTemplate) {
        return new JmsMessageSender(jmsTemplate);
    }

    @Bean
    public MessageListenerContainer messageListenerContainer(ConnectionFactory connectionFactory,
                                                              JmsMessagingProperties properties) {
        return new JmsMessageListenerContainer(connectionFactory, properties);
    }
}
