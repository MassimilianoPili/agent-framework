package com.agentframework.orchestrator.graph;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates visual representations of a Plan's task DAG.
 *
 * <p>Supported formats:
 * <ul>
 *   <li><b>mermaid</b>: Mermaid {@code graph LR} syntax, renderable by Gitea, GitHub, and most
 *       Markdown viewers. Each node shows taskKey, workerType, status, duration, and token usage.
 *       Edges reflect declared {@code dependsOn} relationships.</li>
 *   <li><b>json</b>: Machine-readable adjacency list with full runtime metadata per node.</li>
 * </ul>
 *
 * <p>Token extraction is fail-safe: if the result JSON does not contain provenance data,
 * the token field is omitted from the label rather than throwing.</p>
 */
@Service
public class PlanGraphService {

    private static final Logger log = LoggerFactory.getLogger(PlanGraphService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Worker type → display icon (Unicode, renders in most terminals and browsers). */
    private static final Map<String, String> WORKER_ICONS = Map.ofEntries(
        Map.entry("BE",                  "⚙"),
        Map.entry("FE",                  "🖥"),
        Map.entry("AI_TASK",             "🤖"),
        Map.entry("CONTRACT",            "📋"),
        Map.entry("REVIEW",              "👁"),
        Map.entry("CONTEXT_MANAGER",     "📂"),
        Map.entry("SCHEMA_MANAGER",      "📐"),
        Map.entry("HOOK_MANAGER",        "🪝"),
        Map.entry("AUDIT_MANAGER",       "📊"),
        Map.entry("EVENT_MANAGER",       "⚡"),
        Map.entry("COMPENSATOR_MANAGER", "↩"),
        Map.entry("TASK_MANAGER",        "📌")
    );

    /**
     * Generates a Mermaid {@code graph LR} diagram for the given plan.
     *
     * <p>Node label format:
     * <pre>
     * BE-001
     * ⚙ BE · be-java
     * DONE
     * 45s | 12.3k tk
     * </pre>
     *
     * <p>Edge labels indicate dependency type when detectable from the task key prefix:
     * context-manager tasks produce "context" edges, schema-manager produce "schema" edges,
     * all others produce unlabelled functional dependency arrows.
     */
    public String toMermaid(Plan plan) {
        StringBuilder sb = new StringBuilder("graph LR\n");

        // classDef for each ItemStatus — color-codes nodes by execution state
        sb.append("  classDef st_WAITING    fill:#999999,color:#ffffff,stroke:#666\n");
        sb.append("  classDef st_DISPATCHED fill:#ff9900,color:#000000,stroke:#cc7700\n");
        sb.append("  classDef st_RUNNING    fill:#0066ff,color:#ffffff,stroke:#0044cc\n");
        sb.append("  classDef st_DONE       fill:#44aa44,color:#ffffff,stroke:#228822\n");
        sb.append("  classDef st_FAILED     fill:#cc3333,color:#ffffff,stroke:#aa1111\n");
        sb.append("\n");

        for (PlanItem item : plan.getItems()) {
            String nodeId = toNodeId(item.getTaskKey());
            String label  = buildLabel(item);
            sb.append("  %s[\"%s\"]:::st_%s\n".formatted(nodeId, label, item.getStatus().name()));

            for (String dep : item.getDependsOn()) {
                String edgeLabel = inferEdgeLabel(dep);
                if (edgeLabel.isEmpty()) {
                    sb.append("  %s --> %s\n".formatted(toNodeId(dep), nodeId));
                } else {
                    sb.append("  %s -->|%s| %s\n".formatted(toNodeId(dep), edgeLabel, nodeId));
                }
            }
        }

        return sb.toString();
    }

    /**
     * Returns a JSON adjacency list with full runtime metadata.
     *
     * <p>Format: {@code {"planId":"...","status":"RUNNING","nodes":[...],"edges":[...]}}
     */
    public String toJson(Plan plan) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();

        for (PlanItem item : plan.getItems()) {
            nodes.add(Map.of(
                "taskKey",      item.getTaskKey(),
                "title",        item.getTitle(),
                "workerType",   item.getWorkerType().name(),
                "workerProfile", item.getWorkerProfile() != null ? item.getWorkerProfile() : "",
                "status",       item.getStatus().name(),
                "durationMs",   computeDurationMs(item),
                "tokensUsed",   extractTokens(item)
            ));
            for (String dep : item.getDependsOn()) {
                edges.add(Map.of(
                    "from", dep,
                    "to",   item.getTaskKey(),
                    "type", inferEdgeLabel(dep)
                ));
            }
        }

        try {
            return mapper.writeValueAsString(Map.of(
                "planId", plan.getId().toString(),
                "status", plan.getStatus().name(),
                "nodes",  nodes,
                "edges",  edges
            ));
        } catch (Exception e) {
            log.warn("Failed to serialize plan DAG to JSON: {}", e.getMessage());
            return "{\"error\":\"serialization failed\"}";
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private String buildLabel(PlanItem item) {
        String icon    = WORKER_ICONS.getOrDefault(item.getWorkerType().name(), "◆");
        String profile = item.getWorkerProfile() != null ? " · " + item.getWorkerProfile() : "";

        StringBuilder label = new StringBuilder();
        label.append(item.getTaskKey()).append("\\n");
        label.append(icon).append(" ").append(item.getWorkerType().name()).append(profile).append("\\n");
        label.append(item.getStatus().name());

        String duration = formatDuration(item);
        String tokens   = formatTokens(item);

        if (!duration.isEmpty() || !tokens.isEmpty()) {
            label.append("\\n");
            if (!duration.isEmpty()) label.append(duration);
            if (!duration.isEmpty() && !tokens.isEmpty()) label.append(" | ");
            if (!tokens.isEmpty()) label.append(tokens);
        }

        return label.toString();
    }

    private String formatDuration(PlanItem item) {
        long ms = computeDurationMs(item);
        if (ms <= 0) return "";
        if (ms < 60_000) return (ms / 1000) + "s";
        return (ms / 60_000) + "m" + ((ms % 60_000) / 1000) + "s";
    }

    private long computeDurationMs(PlanItem item) {
        if (item.getDispatchedAt() == null || item.getCompletedAt() == null) return 0;
        return Duration.between(item.getDispatchedAt(), item.getCompletedAt()).toMillis();
    }

    private String formatTokens(PlanItem item) {
        long tokens = extractTokens(item);
        if (tokens <= 0) return "";
        if (tokens < 1000) return tokens + " tk";
        return String.format("%.1fk tk", tokens / 1000.0);
    }

    /**
     * Extracts total token count from the worker result JSON.
     *
     * <p>Tries multiple known paths to stay resilient across worker SDK versions:
     * {@code provenance.tokenUsage.totalTokens}, {@code provenance.totalTokens},
     * {@code tokenUsage.totalTokens}.
     */
    private long extractTokens(PlanItem item) {
        if (item.getResult() == null) return 0;
        try {
            JsonNode root = mapper.readTree(item.getResult());
            // Path 1: provenance.tokenUsage.totalTokens
            long v = root.path("provenance").path("tokenUsage").path("totalTokens").asLong(-1);
            if (v >= 0) return v;
            // Path 2: provenance.totalTokens
            v = root.path("provenance").path("totalTokens").asLong(-1);
            if (v >= 0) return v;
            // Path 3: tokenUsage.totalTokens (flat)
            v = root.path("tokenUsage").path("totalTokens").asLong(-1);
            if (v >= 0) return v;
        } catch (Exception e) {
            log.debug("Could not extract token count from item {} result: {}", item.getTaskKey(), e.getMessage());
        }
        return 0;
    }

    /**
     * Converts a task key to a valid Mermaid node identifier.
     * Hyphens are replaced with underscores (required by Mermaid parser).
     */
    private String toNodeId(String taskKey) {
        return taskKey.replace("-", "_");
    }

    /**
     * Infers a human-readable edge label from the dependency task key prefix.
     * e.g. "CM-001" → "context", "SM-001" → "schema", others → "".
     */
    private String inferEdgeLabel(String depKey) {
        if (depKey == null) return "";
        String upper = depKey.toUpperCase();
        if (upper.startsWith("CM-")) return "context";
        if (upper.startsWith("SM-")) return "schema";
        if (upper.startsWith("HM-")) return "policy";
        return "";
    }
}
