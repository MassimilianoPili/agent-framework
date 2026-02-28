package com.agentframework.messaging.jms;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;

/**
 * JMS implementation of MessageSender.
 * Sends messages to JMS topics (or queues) using JmsTemplate.
 */
public class JmsMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(JmsMessageSender.class);

    private final JmsTemplate jmsTemplate;

    public JmsMessageSender(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    @Override
    public void send(MessageEnvelope envelope) {
        log.debug("Sending JMS message to '{}', id={}", envelope.destination(), envelope.messageId());

        jmsTemplate.send(envelope.destination(), session -> {
            var message = session.createTextMessage(envelope.body());

            if (envelope.messageId() != null) {
                message.setStringProperty("messageId", envelope.messageId());
            }

            // Copy envelope properties as JMS string properties
            envelope.properties().forEach((key, value) -> {
                try {
                    message.setStringProperty(key, value);
                } catch (Exception e) {
                    log.warn("Failed to set JMS property '{}': {}", key, e.getMessage());
                }
            });

            return message;
        });

        log.debug("JMS message sent to '{}', id={}", envelope.destination(), envelope.messageId());
    }
}
