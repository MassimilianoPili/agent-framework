package com.agentframework.worker.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Structured audit logger for tool invocations.
 *
 * <p>Uses a dedicated logger ({@code audit.tools}) so operators can route
 * audit events to a separate sink via logback configuration. MDC fields
 * provide structured context for downstream consumers (ELK, CloudWatch).</p>
 *
 * <p>Outcome semantics:</p>
 * <ul>
 *   <li>{@code SUCCESS} — tool executed normally</li>
 *   <li>{@code FAILURE} — tool threw an exception</li>
 *   <li>{@code DENIED} — blocked by policy (path ownership violation)</li>
 * </ul>
 */
public class ToolAuditLogger {

    private static final Logger audit = LoggerFactory.getLogger("audit.tools");

    private final PolicyProperties properties;

    public ToolAuditLogger(PolicyProperties properties) {
        this.properties = properties;
    }

    public enum Outcome { SUCCESS, FAILURE, DENIED }

    public record ToolAuditEvent(
            String toolName,
            String workerType,
            String workerProfile,
            Outcome outcome,
            long durationMs,
            String inputPreview,
            String policyViolation
    ) {}

    /**
     * Logs a tool call event with MDC context for structured logging.
     */
    public void logToolCall(ToolAuditEvent event) {
        if (!properties.getAudit().isEnabled()) {
            return;
        }

        try {
            MDC.put("tool", event.toolName());
            MDC.put("worker", event.workerType());
            MDC.put("profile", event.workerProfile() != null ? event.workerProfile() : "");
            MDC.put("outcome", event.outcome().name());
            MDC.put("duration_ms", String.valueOf(event.durationMs()));

            if (event.outcome() == Outcome.DENIED) {
                audit.warn("tool_call tool={} worker={} profile={} outcome=DENIED duration={}ms violation=\"{}\"",
                        event.toolName(), event.workerType(), event.workerProfile(),
                        event.durationMs(), event.policyViolation());
            } else {
                String msg = "tool_call tool={} worker={} profile={} outcome={} duration={}ms";
                if (event.inputPreview() != null) {
                    audit.info(msg + " input=\"{}\"",
                            event.toolName(), event.workerType(), event.workerProfile(),
                            event.outcome(), event.durationMs(), event.inputPreview());
                } else {
                    audit.info(msg,
                            event.toolName(), event.workerType(), event.workerProfile(),
                            event.outcome(), event.durationMs());
                }
            }
        } finally {
            MDC.remove("tool");
            MDC.remove("worker");
            MDC.remove("profile");
            MDC.remove("outcome");
            MDC.remove("duration_ms");
        }
    }

    /**
     * Truncates tool input for audit logging, respecting configured max length.
     */
    public String truncateInput(String toolInput) {
        if (toolInput == null) {
            return null;
        }
        if (!properties.getAudit().isIncludeInput()) {
            return null;
        }
        int max = properties.getAudit().getMaxInputLength();
        if (toolInput.length() <= max) {
            return toolInput;
        }
        return toolInput.substring(0, max) + "...";
    }
}
