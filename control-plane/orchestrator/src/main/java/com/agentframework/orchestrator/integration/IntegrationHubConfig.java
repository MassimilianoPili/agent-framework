package com.agentframework.orchestrator.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the External System Integration Hub.
 *
 * <pre>
 * integration-hub:
 *   enabled: false
 *   webhook:
 *     max-payload-bytes: 65536
 *     idempotency-ttl-hours: 72
 *   notification:
 *     max-rules-per-system: 20
 *     batch-size: 10
 *     retry-attempts: 3
 *   adapter:
 *     timeout-seconds: 30
 *     circuit-breaker-threshold: 5
 *     cooldown-seconds: 60
 * </pre>
 *
 * @param webhook      inbound webhook constraints
 * @param notification notification pipeline tuning
 * @param adapter      outbound adapter resilience
 */
@ConfigurationProperties(prefix = "integration-hub")
public record IntegrationHubConfig(
        WebhookConfig webhook,
        NotificationConfig notification,
        AdapterConfig adapter
) {
    public IntegrationHubConfig {
        if (webhook == null) webhook = new WebhookConfig(65536, 72);
        if (notification == null) notification = new NotificationConfig(20, 10, 3);
        if (adapter == null) adapter = new AdapterConfig(30, 5, 60);
    }

    /**
     * @param maxPayloadBytes     max inbound webhook body size (default 64KB)
     * @param idempotencyTtlHours how long to keep idempotency keys for dedup (default 72h)
     */
    public record WebhookConfig(int maxPayloadBytes, int idempotencyTtlHours) {
        public WebhookConfig {
            if (maxPayloadBytes <= 0) maxPayloadBytes = 65536;
            if (idempotencyTtlHours <= 0) idempotencyTtlHours = 72;
        }
    }

    /**
     * @param maxRulesPerSystem max notification rules per target system
     * @param batchSize         batch outbound notifications
     * @param retryAttempts     retries on delivery failure
     */
    public record NotificationConfig(int maxRulesPerSystem, int batchSize, int retryAttempts) {
        public NotificationConfig {
            if (maxRulesPerSystem <= 0) maxRulesPerSystem = 20;
            if (batchSize <= 0) batchSize = 10;
            if (retryAttempts < 0) retryAttempts = 3;
        }
    }

    /**
     * @param timeoutSeconds          adapter call timeout
     * @param circuitBreakerThreshold consecutive failures before opening circuit
     * @param cooldownSeconds         time in OPEN state before HALF_OPEN probe
     */
    public record AdapterConfig(int timeoutSeconds, int circuitBreakerThreshold, int cooldownSeconds) {
        public AdapterConfig {
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
            if (circuitBreakerThreshold <= 0) circuitBreakerThreshold = 5;
            if (cooldownSeconds <= 0) cooldownSeconds = 60;
        }
    }
}
