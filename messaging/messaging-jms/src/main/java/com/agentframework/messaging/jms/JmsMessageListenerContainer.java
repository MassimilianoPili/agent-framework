package com.agentframework.messaging.jms;

import com.agentframework.messaging.MessageHandler;
import com.agentframework.messaging.MessageListenerContainer;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * JMS implementation of MessageListenerContainer.
 * Each subscription creates a DefaultMessageListenerContainer with a durable subscription
 * on a JMS topic. Session-transacted mode is used for ack/nack.
 */
public class JmsMessageListenerContainer implements MessageListenerContainer {

    private static final Logger log = LoggerFactory.getLogger(JmsMessageListenerContainer.class);

    private final ConnectionFactory connectionFactory;
    private final JmsMessagingProperties properties;
    private final List<DefaultMessageListenerContainer> containers = new ArrayList<>();

    public JmsMessageListenerContainer(ConnectionFactory connectionFactory,
                                        JmsMessagingProperties properties) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
    }

    @Override
    public void subscribe(String destination, String group, MessageHandler handler) {
        log.info("Subscribing to JMS destination '{}' with group '{}'", destination, group);

        var container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName(destination);
        container.setPubSubDomain(properties.isPubSubDomain());
        container.setSubscriptionDurable(true);
        container.setDurableSubscriptionName(group);
        container.setClientId(group);
        container.setSessionTransacted(true);
        container.setConcurrency(properties.getConcurrency());

        // Cast to MessageListener explicitly for DMLC compatibility.
        // In session-transacted mode, commit/rollback is handled by the container:
        // - Normal return → session.commit()
        // - Exception thrown → session.rollback() → broker redelivers
        MessageListener listener = message -> {
            try {
                if (message instanceof TextMessage textMessage) {
                    String body = textMessage.getText();
                    handler.handle(body, new TransactedAcknowledgment());
                } else {
                    log.warn("Received non-text JMS message on '{}', ignoring", destination);
                }
            } catch (Exception e) {
                log.error("Error processing JMS message on '{}': {}", destination, e.getMessage(), e);
                throw new RuntimeException("Message processing failed", e);
            }
        };
        container.setMessageListener(listener);

        containers.add(container);
    }

    @Override
    public void start() {
        log.info("Starting {} JMS listener container(s)", containers.size());
        for (var container : containers) {
            container.afterPropertiesSet();
            container.start();
        }
    }

    @Override
    public void stop() {
        log.info("Stopping {} JMS listener container(s)", containers.size());
        for (var container : containers) {
            container.stop();
            container.shutdown();
        }
    }

    /**
     * In session-transacted mode, the DMLC handles commit/rollback automatically:
     * - If the listener returns normally → session.commit()
     * - If the listener throws → session.rollback() → broker redelivers
     *
     * complete() is a no-op (success = normal return).
     * reject() throws to trigger rollback.
     */
    private static class TransactedAcknowledgment implements com.agentframework.messaging.MessageAcknowledgment {

        @Override
        public void complete() {
            // No-op: session-transacted mode commits on normal return
        }

        @Override
        public void reject(String reason) {
            throw new RuntimeException("Message rejected: " + reason);
        }
    }
}
