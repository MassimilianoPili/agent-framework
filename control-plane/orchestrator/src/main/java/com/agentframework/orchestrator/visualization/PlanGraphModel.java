package com.agentframework.orchestrator.visualization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renderer-neutral graph model for plan visualization.
 *
 * <p>Intermediate representation between the domain model (Plan/PlanItem)
 * and the rendering output (Mermaid, D3 JSON). Nodes carry layout coordinates
 * assigned by {@link GraphLayoutEngine}.</p>
 *
 * @param planId   root plan UUID
 * @param status   plan status string
 * @param nodes    graph nodes (one per PlanItem or sub-plan)
 * @param edges    graph edges (dependencies + parent-child)
 * @param metadata additional properties (e.g. makespan, critical path length)
 */
public record PlanGraphModel(
        UUID planId,
        String status,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        Map<String, Object> metadata
) {
    /**
     * A node in the graph.
     *
     * @param id       unique identifier (task key or plan ID)
     * @param label    display label (task key + worker type + status)
     * @param type     node type: TASK, PLAN, EPIC
     * @param status   execution status (WAITING, DONE, FAILED, etc.)
     * @param group    grouping key (worker type or epic name)
     * @param layer    Sugiyama layer index (0 = leftmost)
     * @param x        computed x coordinate (pixels)
     * @param y        computed y coordinate (pixels)
     * @param metadata extra data (duration, tokens, worker profile, etc.)
     */
    public record GraphNode(
            String id,
            String label,
            NodeType type,
            String status,
            String group,
            int layer,
            double x,
            double y,
            Map<String, Object> metadata
    ) {}

    /**
     * An edge in the graph.
     *
     * @param source         source node ID
     * @param target         target node ID
     * @param type           edge type (DEPENDENCY, PARENT_CHILD, COMPENSATION)
     * @param onCriticalPath whether this edge is on the critical path
     */
    public record GraphEdge(
            String source,
            String target,
            EdgeType type,
            boolean onCriticalPath
    ) {}

    public enum NodeType {
        TASK, PLAN, EPIC
    }

    public enum EdgeType {
        DEPENDENCY, PARENT_CHILD, COMPENSATION
    }
}
