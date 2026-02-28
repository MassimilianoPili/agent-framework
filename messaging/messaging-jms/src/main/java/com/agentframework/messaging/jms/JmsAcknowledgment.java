package com.agentframework.messaging.jms;

import com.agentframework.messaging.MessageAcknowledgment;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JMS acknowledgment using session-transacted mode.
 * commit() = message processed successfully (removed from broker).
 * rollback() = message rejected (broker will redeliver or dead-letter).
 */
public class JmsAcknowledgment implements MessageAcknowledgment {

    private static final Logger log = LoggerFactory.getLogger(JmsAcknowledgment.class);

    private final Session session;

    public JmsAcknowledgment(Session session) {
        this.session = session;
    }

    @Override
    public void complete() {
        try {
            session.commit();
        } catch (JMSException e) {
            log.error("Failed to commit JMS session", e);
            throw new RuntimeException("JMS commit failed", e);
        }
    }

    @Override
    public void reject(String reason) {
        try {
            log.warn("Rejecting JMS message: {}", reason);
            session.rollback();
        } catch (JMSException e) {
            log.error("Failed to rollback JMS session", e);
            throw new RuntimeException("JMS rollback failed", e);
        }
    }
}
