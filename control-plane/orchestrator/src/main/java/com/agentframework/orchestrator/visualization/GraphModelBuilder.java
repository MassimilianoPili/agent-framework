package com.agentframework.orchestrator.visualization;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.visualization.PlanGraphModel.*;

import java.time.Duration;
import java.util.*;

/**
 * Builds a {@link PlanGraphModel} from domain objects.
 *
 * <p>Extracts nodes from {@link PlanItem} instances and edges from their
 * {@code dependsOn} relationships. For hierarchical views, sub-plans
 * become PLAN-type nodes connected via PARENT_CHILD edges.</p>
 *
 * <p>This is a pure POJO — no Spring dependencies — for easy unit testing.</p>
 */
public class GraphModelBuilder {

    private static final Map<String, String> WORKER_ICONS = Map.ofEntries(
            Map.entry("BE", "⚙"), Map.entry("FE", "🖥"), Map.entry("AI_TASK", "🤖"),
            Map.entry("CONTRACT", "📋"), Map.entry("REVIEW", "👁"),
            Map.entry("CONTEXT_MANAGER", "📂"), Map.entry("SCHEMA_MANAGER", "📐"),
            Map.entry("HOOK_MANAGER", "🪝"), Map.entry("AUDIT_MANAGER", "📊"),
            Map.entry("EVENT_MANAGER", "⚡"), Map.entry("COMPENSATOR_MANAGER", "↩"),
            Map.entry("TASK_MANAGER", "📌"));

    /**
     * Builds a graph model for a single plan.
     *
     * @param plan       the plan with loaded items
     * @param criticalPath task keys on the critical path (nullable)
     * @return graph model without layout coordinates (layer/x/y = 0)
     */
    public PlanGraphModel buildSinglePlan(Plan plan, Set<String> criticalPath) {
        Set<String> cp = criticalPath != null ? criticalPath : Set.of();
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();

        for (PlanItem item : plan.getItems()) {
            String taskKey = item.getTaskKey();
            String workerType = item.getWorkerType().name();
            String icon = WORKER_ICONS.getOrDefault(workerType, "◆");

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("workerType", workerType);
            if (item.getWorkerProfile() != null) meta.put("workerProfile", item.getWorkerProfile());
            meta.put("durationMs", computeDurationMs(item));
            meta.put("onCriticalPath", cp.contains(taskKey));

            String label = taskKey + " " + icon + " " + workerType;

            nodes.add(new GraphNode(
                    taskKey, label, NodeType.TASK,
                    item.getStatus().name(),
                    workerType, // group by worker type
                    0, 0, 0,   // layout computed later
                    meta));

            for (String dep : item.getDependsOn()) {
                edges.add(new GraphEdge(dep, taskKey, EdgeType.DEPENDENCY, cp.contains(dep) && cp.contains(taskKey)));
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("itemCount", plan.getItems().size());
        metadata.put("criticalPathLength", cp.size());

        return new PlanGraphModel(plan.getId(), plan.getStatus().name(), nodes, edges, metadata);
    }

    /**
     * Builds a hierarchical graph model for a plan and its sub-plans.
     *
     * @param rootPlan  the root plan
     * @param subPlans  child plans (may be empty)
     * @param criticalPaths per-plan critical paths (planId → set of task keys)
     * @return combined graph model
     */
    public PlanGraphModel buildHierarchy(Plan rootPlan, List<Plan> subPlans,
                                          Map<UUID, Set<String>> criticalPaths) {
        List<GraphNode> allNodes = new ArrayList<>();
        List<GraphEdge> allEdges = new ArrayList<>();

        // Root plan as a PLAN node
        allNodes.add(new GraphNode(
                rootPlan.getId().toString(),
                "Root: " + truncate(rootPlan.getSpec(), 40),
                NodeType.PLAN,
                rootPlan.getStatus().name(),
                "root", 0, 0, 0,
                Map.of("depth", rootPlan.getDepth())));

        // Add root items
        PlanGraphModel rootModel = buildSinglePlan(rootPlan, criticalPaths.getOrDefault(rootPlan.getId(), Set.of()));
        allNodes.addAll(rootModel.nodes());
        allEdges.addAll(rootModel.edges());

        // Connect root plan node to its source items (no predecessors)
        for (GraphNode node : rootModel.nodes()) {
            boolean hasIncomingEdge = rootModel.edges().stream()
                    .anyMatch(e -> e.target().equals(node.id()));
            if (!hasIncomingEdge) {
                allEdges.add(new GraphEdge(rootPlan.getId().toString(), node.id(), EdgeType.PARENT_CHILD, false));
            }
        }

        // Sub-plans
        for (Plan sub : subPlans) {
            String subPlanId = sub.getId().toString();

            allNodes.add(new GraphNode(
                    subPlanId,
                    "Sub: " + truncate(sub.getSpec(), 40),
                    NodeType.PLAN,
                    sub.getStatus().name(),
                    "sub-plan", 0, 0, 0,
                    Map.of("depth", sub.getDepth())));

            allEdges.add(new GraphEdge(rootPlan.getId().toString(), subPlanId, EdgeType.PARENT_CHILD, false));

            PlanGraphModel subModel = buildSinglePlan(sub, criticalPaths.getOrDefault(sub.getId(), Set.of()));
            // Prefix sub-plan node IDs to avoid collision
            for (GraphNode node : subModel.nodes()) {
                allNodes.add(new GraphNode(
                        subPlanId + "/" + node.id(),
                        node.label(), node.type(), node.status(), node.group(),
                        node.layer(), node.x(), node.y(), node.metadata()));
            }
            for (GraphEdge edge : subModel.edges()) {
                allEdges.add(new GraphEdge(
                        subPlanId + "/" + edge.source(),
                        subPlanId + "/" + edge.target(),
                        edge.type(), edge.onCriticalPath()));
            }

            // Connect sub-plan node to its source items
            for (GraphNode node : subModel.nodes()) {
                boolean hasIncoming = subModel.edges().stream()
                        .anyMatch(e -> e.target().equals(node.id()));
                if (!hasIncoming) {
                    allEdges.add(new GraphEdge(subPlanId, subPlanId + "/" + node.id(), EdgeType.PARENT_CHILD, false));
                }
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("totalPlans", 1 + subPlans.size());
        metadata.put("totalNodes", allNodes.size());
        metadata.put("totalEdges", allEdges.size());

        return new PlanGraphModel(rootPlan.getId(), rootPlan.getStatus().name(),
                allNodes, allEdges, metadata);
    }

    private long computeDurationMs(PlanItem item) {
        if (item.getDispatchedAt() == null || item.getCompletedAt() == null) return 0;
        return Duration.between(item.getDispatchedAt(), item.getCompletedAt()).toMillis();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
