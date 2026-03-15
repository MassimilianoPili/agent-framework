package com.agentframework.orchestrator.integration;

import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.integration.ExternalSystemAdapter.IntegrationEvent;
import com.agentframework.orchestrator.integration.ExternalSystemAdapter.IntegrationEvent.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Notification pipeline that listens to {@link SpringPlanEvent} and routes
 * matching events to external systems based on configurable notification rules.
 *
 * <p>Rules are loaded from the {@code notification_rules} table and cached
 * with a 60-second TTL to minimize DB queries per event.</p>
 *
 * <p>Template placeholders supported in notification templates:</p>
 * <ul>
 *   <li>{@code ${planId}} — plan UUID</li>
 *   <li>{@code ${eventType}} — event type string</li>
 *   <li>{@code ${taskKey}} — task key (item-level events)</li>
 *   <li>{@code ${workerProfile}} — worker profile</li>
 *   <li>{@code ${success}} — true/false</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "integration-hub", name = "enabled", havingValue = "true", matchIfMissing = false)
public class NotificationPipeline {

    private static final Logger log = LoggerFactory.getLogger(NotificationPipeline.class);
    private static final long CACHE_TTL_MS = 60_000;

    private final IntegrationHubService hubService;
    private final JdbcTemplate jdbc;

    private volatile List<NotificationRule> cachedRules = new CopyOnWriteArrayList<>();
    private volatile long cacheLoadedAt = 0;

    public NotificationPipeline(IntegrationHubService hubService, JdbcTemplate jdbc) {
        this.hubService = hubService;
        this.jdbc = jdbc;
    }

    /**
     * Handles plan events and dispatches matching notifications.
     */
    @Async
    @EventListener
    public void onPlanEvent(SpringPlanEvent event) {
        List<NotificationRule> rules = getActiveRules();
        if (rules.isEmpty()) return;

        for (NotificationRule rule : rules) {
            if (matches(rule, event)) {
                try {
                    String payload = renderTemplate(rule.template(), event);
                    IntegrationEvent integrationEvent = new IntegrationEvent(
                            UUID.randomUUID(), Direction.OUTBOUND,
                            rule.targetSystem(), event.eventType(),
                            payload, event.planId(), null);

                    hubService.dispatch(integrationEvent);

                    log.debug("Notification fired: rule={}, event={}, target={}",
                            rule.name(), event.eventType(), rule.targetSystem());
                } catch (Exception e) {
                    log.error("Failed to fire notification rule={}: {}", rule.name(), e.getMessage());
                }
            }
        }
    }

    /**
     * Returns active notification rules, using cached version if fresh.
     */
    List<NotificationRule> getActiveRules() {
        long now = System.currentTimeMillis();
        if (now - cacheLoadedAt > CACHE_TTL_MS) {
            reloadRules();
        }
        return cachedRules;
    }

    private void reloadRules() {
        try {
            List<NotificationRule> rules = jdbc.query(
                    "SELECT id, name, event_type_pattern, target_system, target_config::text, template, severity_filter FROM notification_rules WHERE enabled = true",
                    (rs, rowNum) -> new NotificationRule(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("name"),
                            rs.getString("event_type_pattern"),
                            rs.getString("target_system"),
                            rs.getString("target_config"),
                            rs.getString("template"),
                            rs.getString("severity_filter")));
            cachedRules = new CopyOnWriteArrayList<>(rules);
            cacheLoadedAt = System.currentTimeMillis();
            log.debug("Reloaded {} active notification rules", rules.size());
        } catch (Exception e) {
            log.warn("Failed to reload notification rules: {}", e.getMessage());
        }
    }

    private boolean matches(NotificationRule rule, SpringPlanEvent event) {
        // Match event type pattern (supports * wildcard)
        String pattern = rule.eventTypePattern()
                .replace(".", "\\.")
                .replace("*", ".*");
        if (!Pattern.matches(pattern, event.eventType())) {
            return false;
        }

        // Severity filter (optional)
        if (rule.severityFilter() != null) {
            boolean isCritical = event.eventType().contains("FAILED")
                    || event.eventType().contains("CRITICALITY")
                    || event.eventType().contains("DRIFT");
            if ("CRITICAL".equals(rule.severityFilter()) && !isCritical) {
                return false;
            }
        }

        return true;
    }

    private String renderTemplate(String template, SpringPlanEvent event) {
        if (template == null || template.isBlank()) {
            return "{\"eventType\":\"" + event.eventType()
                    + "\",\"planId\":\"" + event.planId()
                    + "\",\"success\":" + event.success() + "}";
        }

        String result = template;
        result = result.replace("${planId}", String.valueOf(event.planId()));
        result = result.replace("${eventType}", event.eventType());
        result = result.replace("${taskKey}", event.taskKey() != null ? event.taskKey() : "");
        result = result.replace("${workerProfile}", event.workerProfile() != null ? event.workerProfile() : "");
        result = result.replace("${success}", String.valueOf(event.success()));
        return result;
    }

    // --- Types ---

    record NotificationRule(
            UUID id,
            String name,
            String eventTypePattern,
            String targetSystem,
            String targetConfig,
            String template,
            String severityFilter
    ) {}
}
