package com.agentframework.messaging.jms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JMS messaging.
 * Spring Artemis properties (spring.artemis.*) are handled by Spring Boot auto-configuration.
 * These properties control framework-specific JMS behavior.
 */
@ConfigurationProperties(prefix = "messaging.jms")
public class JmsMessagingProperties {

    /**
     * Whether to use JMS topics (pub/sub) instead of queues.
     * Default: true (topics match the Service Bus / Redis Streams pub/sub model).
     */
    private boolean pubSubDomain = true;

    /**
     * Concurrency for message listener containers (min-max format).
     */
    private String concurrency = "1-3";

    /**
     * Maximum number of redelivery attempts before the message is dead-lettered.
     */
    private int maxRedeliveryAttempts = 5;

    public boolean isPubSubDomain() {
        return pubSubDomain;
    }

    public void setPubSubDomain(boolean pubSubDomain) {
        this.pubSubDomain = pubSubDomain;
    }

    public String getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(String concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxRedeliveryAttempts() {
        return maxRedeliveryAttempts;
    }

    public void setMaxRedeliveryAttempts(int maxRedeliveryAttempts) {
        this.maxRedeliveryAttempts = maxRedeliveryAttempts;
    }
}
