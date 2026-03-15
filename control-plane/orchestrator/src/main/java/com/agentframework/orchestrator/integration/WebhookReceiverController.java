package com.agentframework.orchestrator.integration;

import com.agentframework.orchestrator.integration.ExternalSystemAdapter.IntegrationEvent;
import com.agentframework.orchestrator.integration.ExternalSystemAdapter.IntegrationEvent.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller receiving inbound webhooks from external systems.
 *
 * <p>Deduplicates using {@code X-Idempotency-Key} (or {@code X-Request-ID}) header
 * backed by a DB unique constraint. Duplicate requests return 200 OK with cached result.</p>
 *
 * <p>Webhook flow:</p>
 * <ol>
 *   <li>Extract idempotency key from headers</li>
 *   <li>Check DB for existing event with same key (dedup)</li>
 *   <li>Route to adapter via {@link IntegrationHubService#processInbound}</li>
 *   <li>Persist event to {@code integration_events} table</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@ConditionalOnProperty(prefix = "integration-hub", name = "enabled", havingValue = "true", matchIfMissing = false)
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    private final IntegrationHubService hubService;
    private final IntegrationHubConfig config;
    private final JdbcTemplate jdbc;

    public WebhookReceiverController(IntegrationHubService hubService,
                                      IntegrationHubConfig config,
                                      JdbcTemplate jdbc) {
        this.hubService = hubService;
        this.config = config;
        this.jdbc = jdbc;
    }

    /**
     * Receives a webhook from an external system.
     *
     * @param systemType system type path variable (e.g., "jira", "gitea", "jenkins")
     * @param body       raw JSON payload
     * @param idempotencyKey from X-Idempotency-Key or X-Request-ID header
     * @param requestId  fallback from X-Request-ID header
     * @return 200 OK on success or duplicate, 400 on validation error
     */
    @PostMapping("/{systemType}")
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @PathVariable String systemType,
            @RequestBody String body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) @Nullable String requestId,
            @RequestHeader Map<String, String> headers) {

        // Validate payload size
        if (body.length() > config.webhook().maxPayloadBytes()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "payload_too_large",
                    "maxBytes", config.webhook().maxPayloadBytes()));
        }

        // Resolve idempotency key
        String key = idempotencyKey != null ? idempotencyKey : requestId;
        if (key == null) {
            key = UUID.randomUUID().toString();
        }

        // Dedup check: try to insert, catch unique constraint violation
        UUID eventId = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO integration_events (id, idempotency_key, direction, system_type,
                    event_type, payload, status, created_at)
                VALUES (?::uuid, ?, 'INBOUND', ?, 'WEBHOOK', ?::jsonb, 'PENDING', NOW())
                """, eventId.toString(), key, systemType.toUpperCase(), body);
        } catch (DataIntegrityViolationException e) {
            // Duplicate idempotency key — return cached success
            log.debug("Duplicate webhook idempotency key: {}", key);
            return ResponseEntity.ok(Map.of(
                    "status", "duplicate",
                    "idempotencyKey", key));
        }

        // Route to adapter
        IntegrationEvent parsed = hubService.processInbound(systemType.toUpperCase(), headers, body);

        // Update event status
        String status = parsed != null ? "DELIVERED" : "IGNORED";
        jdbc.update("UPDATE integration_events SET status = ?, processed_at = NOW() WHERE id = ?::uuid",
                status, eventId.toString());

        log.info("Webhook received from {} (key={}, status={})", systemType, key, status);

        return ResponseEntity.ok(Map.of(
                "status", status.toLowerCase(),
                "eventId", eventId.toString(),
                "idempotencyKey", key));
    }

    /**
     * Health check for webhook endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "registeredSystems", hubService.registeredSystems(),
                "circuitBreakers", hubService.circuitBreakerStatus()));
    }
}
