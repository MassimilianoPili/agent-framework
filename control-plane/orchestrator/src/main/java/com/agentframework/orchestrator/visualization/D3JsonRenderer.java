package com.agentframework.orchestrator.visualization;

import com.agentframework.orchestrator.visualization.PlanGraphModel.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link PlanGraphModel} as D3.js-compatible JSON.
 *
 * <p>Output format:</p>
 * <pre>
 * {
 *   "planId": "...",
 *   "status": "RUNNING",
 *   "nodes": [
 *     {"id": "BE-001", "label": "...", "type": "TASK", "status": "DONE",
 *      "group": "BE", "layer": 0, "x": 0, "y": 0, "meta": {...}}
 *   ],
 *   "links": [
 *     {"source": "BE-001", "target": "FE-001", "type": "DEPENDENCY", "critical": false}
 *   ],
 *   "meta": {...}
 * }
 * </pre>
 *
 * <p>Pure POJO, no Spring or Jackson dependencies — manual JSON assembly.</p>
 */
public class D3JsonRenderer {

    /**
     * Renders the graph model to D3-compatible JSON string.
     */
    public String render(PlanGraphModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Plan metadata
        sb.append("\"planId\":\"").append(model.planId()).append("\",");
        sb.append("\"status\":\"").append(model.status()).append("\",");

        // Nodes
        sb.append("\"nodes\":[");
        List<GraphNode> nodes = model.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sb.append(",");
            renderNode(sb, nodes.get(i));
        }
        sb.append("],");

        // Links
        sb.append("\"links\":[");
        List<GraphEdge> edges = model.edges();
        for (int i = 0; i < edges.size(); i++) {
            if (i > 0) sb.append(",");
            renderEdge(sb, edges.get(i));
        }
        sb.append("],");

        // Metadata
        sb.append("\"meta\":");
        renderMap(sb, model.metadata());

        sb.append("}");
        return sb.toString();
    }

    private void renderNode(StringBuilder sb, GraphNode node) {
        sb.append("{");
        sb.append("\"id\":\"").append(escapeJson(node.id())).append("\",");
        sb.append("\"label\":\"").append(escapeJson(node.label())).append("\",");
        sb.append("\"type\":\"").append(node.type()).append("\",");
        sb.append("\"status\":\"").append(node.status()).append("\",");
        sb.append("\"group\":\"").append(escapeJson(node.group())).append("\",");
        sb.append("\"layer\":").append(node.layer()).append(",");
        sb.append("\"x\":").append(node.x()).append(",");
        sb.append("\"y\":").append(node.y()).append(",");
        sb.append("\"meta\":");
        renderMap(sb, node.metadata());
        sb.append("}");
    }

    private void renderEdge(StringBuilder sb, GraphEdge edge) {
        sb.append("{");
        sb.append("\"source\":\"").append(escapeJson(edge.source())).append("\",");
        sb.append("\"target\":\"").append(escapeJson(edge.target())).append("\",");
        sb.append("\"type\":\"").append(edge.type()).append("\",");
        sb.append("\"critical\":").append(edge.onCriticalPath());
        sb.append("}");
    }

    private void renderMap(StringBuilder sb, Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            sb.append("{}");
            return;
        }

        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            renderValue(sb, entry.getValue());
        }
        sb.append("}");
    }

    @SuppressWarnings("unchecked")
    private void renderValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            renderMap(sb, (Map<String, Object>) value);
        } else {
            sb.append("\"").append(escapeJson(value.toString())).append("\"");
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
