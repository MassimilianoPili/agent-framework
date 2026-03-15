package com.agentframework.orchestrator.visualization;

import com.agentframework.orchestrator.visualization.PlanGraphModel.*;

import java.util.*;

/**
 * Renders a {@link PlanGraphModel} as a Mermaid graph diagram.
 *
 * <p>Output is compatible with Gitea, GitHub, and Mermaid-enabled Markdown
 * viewers. Uses {@code graph LR} for left-to-right layout (matching the
 * layered Sugiyama structure).</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Color-coded nodes by status (classDef)</li>
 *   <li>Subgraphs for PLAN nodes (hierarchy)</li>
 *   <li>Bold edges for critical path</li>
 *   <li>Worker type icons in labels</li>
 * </ul>
 *
 * <p>Pure POJO, no Spring dependencies.</p>
 */
public class MermaidRenderer {

    /**
     * Renders the graph model to Mermaid syntax.
     */
    public String render(PlanGraphModel model) {
        StringBuilder sb = new StringBuilder("graph LR\n");

        // Status classDefs
        sb.append("  classDef st_WAITING    fill:#999999,color:#ffffff,stroke:#666\n");
        sb.append("  classDef st_READY      fill:#6699cc,color:#ffffff,stroke:#4477aa\n");
        sb.append("  classDef st_DISPATCHED fill:#ff9900,color:#000000,stroke:#cc7700\n");
        sb.append("  classDef st_RUNNING    fill:#0066ff,color:#ffffff,stroke:#0044cc\n");
        sb.append("  classDef st_DONE       fill:#44aa44,color:#ffffff,stroke:#228822\n");
        sb.append("  classDef st_FAILED     fill:#cc3333,color:#ffffff,stroke:#aa1111\n");
        sb.append("  classDef st_COMPLETED  fill:#44aa44,color:#ffffff,stroke:#228822\n");
        sb.append("  classDef st_PENDING    fill:#999999,color:#ffffff,stroke:#666\n");
        sb.append("  classDef st_PAUSED     fill:#cc9900,color:#000000,stroke:#aa7700\n");
        sb.append("  classDef st_CANCELLED  fill:#666666,color:#ffffff,stroke:#444\n");
        sb.append("  classDef plan_node     fill:#335577,color:#ffffff,stroke:#224466\n");
        sb.append("\n");

        // Separate PLAN nodes (render as subgraphs) and TASK nodes
        Map<String, List<GraphNode>> planChildren = new LinkedHashMap<>();
        List<GraphNode> taskNodes = new ArrayList<>();
        Set<String> planNodeIds = new HashSet<>();

        for (GraphNode node : model.nodes()) {
            if (node.type() == NodeType.PLAN) {
                planNodeIds.add(node.id());
                planChildren.putIfAbsent(node.id(), new ArrayList<>());
            } else {
                taskNodes.add(node);
            }
        }

        // Assign task nodes to their parent plan (via PARENT_CHILD edges)
        Map<String, String> nodeToParent = new HashMap<>();
        for (GraphEdge edge : model.edges()) {
            if (edge.type() == EdgeType.PARENT_CHILD && planNodeIds.contains(edge.source())) {
                nodeToParent.put(edge.target(), edge.source());
            }
        }

        for (GraphNode task : taskNodes) {
            String parent = nodeToParent.get(task.id());
            if (parent != null) {
                planChildren.computeIfAbsent(parent, k -> new ArrayList<>()).add(task);
            }
        }

        // Render plan subgraphs
        for (Map.Entry<String, List<GraphNode>> entry : planChildren.entrySet()) {
            String planId = entry.getKey();
            List<GraphNode> children = entry.getValue();

            GraphNode planNode = model.nodes().stream()
                    .filter(n -> n.id().equals(planId))
                    .findFirst().orElse(null);

            String subLabel = planNode != null ? planNode.label() : planId;
            sb.append("  subgraph ").append(toNodeId(planId)).append("[\"").append(escapeLabel(subLabel)).append("\"]\n");

            for (GraphNode child : children) {
                renderNode(sb, child, "    ");
            }
            sb.append("  end\n");
            if (planNode != null) {
                sb.append("  class ").append(toNodeId(planId)).append(" plan_node\n");
            }
        }

        // Render orphan task nodes (no plan parent)
        for (GraphNode task : taskNodes) {
            if (!nodeToParent.containsKey(task.id())) {
                renderNode(sb, task, "  ");
            }
        }

        sb.append("\n");

        // Render edges
        for (GraphEdge edge : model.edges()) {
            if (edge.type() == EdgeType.PARENT_CHILD) continue; // handled by subgraph structure

            String from = toNodeId(edge.source());
            String to = toNodeId(edge.target());

            if (edge.onCriticalPath()) {
                sb.append("  ").append(from).append(" ==> ").append(to).append("\n");
            } else {
                sb.append("  ").append(from).append(" --> ").append(to).append("\n");
            }
        }

        return sb.toString();
    }

    private void renderNode(StringBuilder sb, GraphNode node, String indent) {
        String nodeId = toNodeId(node.id());
        String label = escapeLabel(node.label());
        sb.append(indent).append(nodeId).append("[\"").append(label).append("\"]");
        sb.append(":::st_").append(node.status()).append("\n");
    }

    private String toNodeId(String id) {
        return id.replace("-", "_").replace("/", "__").replace(".", "_");
    }

    private String escapeLabel(String label) {
        return label.replace("\"", "'").replace("\n", "\\n");
    }
}
