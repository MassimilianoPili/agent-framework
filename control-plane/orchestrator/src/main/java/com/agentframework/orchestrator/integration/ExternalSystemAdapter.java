package com.agentframework.orchestrator.integration;

import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Adapter interface for external system integration.
 *
 * <p>Each adapter connects the framework to one external system type
 * (issue tracker, CI/CD, notification channel). Concrete adapters
 * (Jira, Gitea, Jenkins, Slack) are implemented as separate files
 * and registered as Spring beans.</p>
 *
 * <p>Non-sealed to allow downstream extension without modifying this file.</p>
 */
public interface ExternalSystemAdapter {

    /**
     * Returns the system type identifier (e.g., "JIRA", "GITEA", "JENKINS", "SLACK").
     */
    String systemType();

    /**
     * Pushes an integration event to the external system.
     *
     * @param event the event to push
     * @return external reference ID (e.g., Jira issue key), or null if fire-and-forget
     */
    @Nullable
    String pushEvent(IntegrationEvent event);

    /**
     * Pulls current status from the external system.
     *
     * @param externalRef the external reference (e.g., Jira issue key, Jenkins build number)
     * @return status map with system-specific fields, or null if unavailable
     */
    @Nullable
    Map<String, Object> pullStatus(String externalRef);

    /**
     * Returns true if this adapter supports inbound webhooks.
     * Default: false.
     */
    default boolean supportsWebhooks() {
        return false;
    }

    /**
     * Processes an inbound webhook payload.
     *
     * @param headers  HTTP headers from the webhook request
     * @param payload  raw JSON payload
     * @return parsed event, or null if the webhook should be ignored
     */
    @Nullable
    default IntegrationEvent processWebhook(Map<String, String> headers, String payload) {
        return null;
    }

    // --- Types ---

    /**
     * An integration event flowing between the framework and an external system.
     *
     * @param id             event UUID
     * @param direction      INBOUND (webhook) or OUTBOUND (notification)
     * @param systemType     target/source system type
     * @param eventType      event type (e.g., "PLAN_COMPLETED", "TASK_FAILED")
     * @param payload        JSON payload
     * @param planId         associated plan ID, if any
     * @param idempotencyKey deduplication key for inbound events
     */
    record IntegrationEvent(
            UUID id,
            Direction direction,
            String systemType,
            String eventType,
            String payload,
            @Nullable UUID planId,
            @Nullable String idempotencyKey
    ) {
        public enum Direction { INBOUND, OUTBOUND }
    }
}
