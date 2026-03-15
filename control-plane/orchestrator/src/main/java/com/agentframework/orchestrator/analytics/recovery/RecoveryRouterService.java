package com.agentframework.orchestrator.analytics.recovery;

import com.agentframework.orchestrator.analytics.mast.SelfHealingRouter;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Graph-based recovery router using Dijkstra's algorithm on task dependency DAG.
 *
 * <p>Implements the approach validated by Bholani "Self-Healing Router"
 * (arXiv:2603.01548, John Deere/MIT) which achieved -93% LLM control-plane calls
 * by using deterministic Dijkstra routing on tool dependency graphs.</p>
 *
 * <p>When a task fails:</p>
 * <ol>
 *   <li>Reweight edges to/from the failed task to INFINITY</li>
 *   <li>Find the shortest alternative path from plan start to plan completion</li>
 *   <li>If no feasible path exists, fall back to MAST-based SelfHealingRouter</li>
 * </ol>
 *
 * <p>This service complements SelfHealingRouter: RecoveryRouter is graph-routing
 * (deterministic, structural), SelfHealingRouter is classification-based (heuristic,
 * failure-mode specific).</p>
 *
 * @see <a href="https://arxiv.org/abs/2603.01548">Bholani, Self-Healing Router (2026)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.recovery-router", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(RecoveryRouterConfig.class)
public class RecoveryRouterService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRouterService.class);

    private final Optional<SelfHealingRouter> selfHealingRouter;
    private final RecoveryRouterConfig config;

    private final ConcurrentHashMap<String, Double> failedEdgeWeights = new ConcurrentHashMap<>();

    public RecoveryRouterService(Optional<SelfHealingRouter> selfHealingRouter,
                                  RecoveryRouterConfig config) {
        this.selfHealingRouter = selfHealingRouter;
        this.config = config;
    }

    /**
     * Finds an alternative execution path after a task failure.
     *
     * @param failedItem the failed plan item
     * @param plan       the current plan with all items and dependencies
     * @return recovery path (shortest alternative) or infeasible path with fallback strategy
     */
    public RecoveryPath findAlternativePath(PlanItem failedItem, Plan plan) {
        if (failedItem == null || plan == null || plan.getItems() == null) {
            return new RecoveryPath(List.of(), Double.MAX_VALUE, false, "INVALID_INPUT");
        }

        // Reweight edges to/from the failed task
        reweightOnFailure(failedItem.getTaskKey());

        // Build adjacency graph from plan DAG
        Map<String, List<GraphEdge>> graph = buildTaskGraph(plan);

        // Identify start nodes (no dependencies) and end nodes (no dependents)
        Set<String> startNodes = findStartNodes(plan);
        Set<String> endNodes = findEndNodes(plan);

        if (startNodes.isEmpty() || endNodes.isEmpty()) {
            return new RecoveryPath(List.of(), Double.MAX_VALUE, false, "NO_START_OR_END_NODES");
        }

        // Run Dijkstra from a virtual source to virtual sink
        List<String> path = dijkstra(graph, startNodes, endNodes, plan);

        if (path.isEmpty()) {
            // No feasible path — fallback to MAST SelfHealingRouter
            log.info("No feasible path found after failure of task={}, falling back to SelfHealingRouter",
                    failedItem.getTaskKey());

            if (selfHealingRouter.isPresent()) {
                return new RecoveryPath(List.of(), Double.MAX_VALUE, false, "MAST_FALLBACK");
            }
            return new RecoveryPath(List.of(), Double.MAX_VALUE, false, "NO_FEASIBLE_PATH");
        }

        double totalWeight = computePathWeight(graph, path);
        log.debug("Recovery path found: {} nodes, weight={}", path.size(), String.format("%.2f", totalWeight));

        return new RecoveryPath(path, totalWeight, true, "DIJKSTRA_REROUTE");
    }

    /**
     * Marks edges to/from a failed task as effectively infinite weight.
     */
    public void reweightOnFailure(String failedTaskKey) {
        failedEdgeWeights.put(failedTaskKey, config.infinityWeight());
    }

    /**
     * Clears failure weights (e.g., after successful recovery).
     */
    public void clearFailureWeights() {
        failedEdgeWeights.clear();
    }

    /**
     * Builds a weighted directed graph from plan item dependencies.
     *
     * @param plan the plan containing items with dependency relationships
     * @return adjacency list: taskKey → list of outgoing edges
     */
    public Map<String, List<GraphEdge>> buildTaskGraph(Plan plan) {
        Map<String, List<GraphEdge>> graph = new LinkedHashMap<>();

        for (PlanItem item : plan.getItems()) {
            String taskKey = item.getTaskKey();
            graph.computeIfAbsent(taskKey, k -> new ArrayList<>());
        }

        // Build edges from dependencies: if B depends on A, then A → B
        for (PlanItem item : plan.getItems()) {
            String toKey = item.getTaskKey();
            List<String> deps = item.getDependsOn();
            if (deps != null) {
                for (String fromKey : deps) {
                    double weight = computeEdgeWeight(fromKey, toKey, plan);
                    graph.computeIfAbsent(fromKey, k -> new ArrayList<>())
                            .add(new GraphEdge(fromKey, toKey, weight));
                }
            }
        }

        return graph;
    }

    // ── Dijkstra ────────────────────────────────────────────────────────────

    private List<String> dijkstra(Map<String, List<GraphEdge>> graph,
                                   Set<String> startNodes, Set<String> endNodes, Plan plan) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<Map.Entry<String, Double>> pq = new PriorityQueue<>(
                Comparator.comparingDouble(Map.Entry::getValue));

        // Initialize distances
        for (String node : graph.keySet()) {
            dist.put(node, Double.MAX_VALUE);
        }
        for (String start : startNodes) {
            dist.put(start, 0.0);
            pq.add(Map.entry(start, 0.0));
        }

        // Dijkstra main loop
        while (!pq.isEmpty()) {
            var current = pq.poll();
            String u = current.getKey();
            double d = current.getValue();

            if (d > dist.getOrDefault(u, Double.MAX_VALUE)) continue;

            // Check path length constraint
            int pathLength = countPathLength(prev, u);
            if (pathLength > config.maxPathLength()) continue;

            // Check if we reached an end node
            if (endNodes.contains(u) && d < Double.MAX_VALUE / 2) {
                return reconstructPath(prev, u);
            }

            List<GraphEdge> edges = graph.getOrDefault(u, List.of());
            for (GraphEdge edge : edges) {
                double newDist = d + edge.weight();
                if (newDist < dist.getOrDefault(edge.to(), Double.MAX_VALUE)) {
                    dist.put(edge.to(), newDist);
                    prev.put(edge.to(), u);
                    pq.add(Map.entry(edge.to(), newDist));
                }
            }
        }

        // Check if any end node is reachable
        String bestEnd = endNodes.stream()
                .filter(n -> dist.getOrDefault(n, Double.MAX_VALUE) < Double.MAX_VALUE / 2)
                .min(Comparator.comparingDouble(n -> dist.get(n)))
                .orElse(null);

        return bestEnd != null ? reconstructPath(prev, bestEnd) : List.of();
    }

    private List<String> reconstructPath(Map<String, String> prev, String end) {
        LinkedList<String> path = new LinkedList<>();
        String current = end;
        while (current != null) {
            path.addFirst(current);
            current = prev.get(current);
        }
        return path;
    }

    private int countPathLength(Map<String, String> prev, String node) {
        int length = 0;
        String current = node;
        while (prev.containsKey(current)) {
            length++;
            current = prev.get(current);
        }
        return length;
    }

    // ── Graph helpers ───────────────────────────────────────────────────────

    private double computeEdgeWeight(String from, String to, Plan plan) {
        // Base weight: 1.0 (unit cost per edge)
        double weight = 1.0;

        // Penalty for failed tasks
        if (failedEdgeWeights.containsKey(from)) {
            weight = failedEdgeWeights.get(from);
        }
        if (failedEdgeWeights.containsKey(to)) {
            weight = failedEdgeWeights.get(to);
        }

        // Bonus for already-completed tasks (prefer paths through DONE items)
        PlanItem toItem = findItem(plan, to);
        if (toItem != null && toItem.getStatus() == ItemStatus.DONE) {
            weight *= 0.1; // Strongly prefer completed paths
        }

        return weight;
    }

    private double computePathWeight(Map<String, List<GraphEdge>> graph, List<String> path) {
        double total = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            String from = path.get(i);
            String to = path.get(i + 1);
            List<GraphEdge> edges = graph.getOrDefault(from, List.of());
            double edgeWeight = edges.stream()
                    .filter(e -> e.to().equals(to))
                    .mapToDouble(GraphEdge::weight)
                    .findFirst()
                    .orElse(1.0);
            total += edgeWeight;
        }
        return total;
    }

    private Set<String> findStartNodes(Plan plan) {
        Set<String> starts = new LinkedHashSet<>();
        for (PlanItem item : plan.getItems()) {
            if (item.getDependsOn() == null || item.getDependsOn().isEmpty()) {
                starts.add(item.getTaskKey());
            }
        }
        return starts;
    }

    private Set<String> findEndNodes(Plan plan) {
        Set<String> allDeps = new HashSet<>();
        for (PlanItem item : plan.getItems()) {
            if (item.getDependsOn() != null) {
                allDeps.addAll(item.getDependsOn());
            }
        }
        Set<String> ends = new LinkedHashSet<>();
        for (PlanItem item : plan.getItems()) {
            if (!allDeps.contains(item.getTaskKey())) {
                ends.add(item.getTaskKey());
            }
        }
        return ends;
    }

    private PlanItem findItem(Plan plan, String taskKey) {
        return plan.getItems().stream()
                .filter(i -> taskKey.equals(i.getTaskKey()))
                .findFirst()
                .orElse(null);
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Recovery path found by Dijkstra routing.
     *
     * @param taskKeys    ordered list of task keys forming the recovery path
     * @param totalWeight sum of edge weights along the path
     * @param feasible    true if a valid path was found
     * @param strategy    routing strategy used (DIJKSTRA_REROUTE, MAST_FALLBACK, NO_FEASIBLE_PATH)
     */
    public record RecoveryPath(
            List<String> taskKeys,
            double totalWeight,
            boolean feasible,
            String strategy
    ) {}

    /**
     * A weighted directed edge in the task graph.
     *
     * @param from   source task key
     * @param to     destination task key
     * @param weight edge weight (higher = costlier to traverse)
     */
    public record GraphEdge(
            String from,
            String to,
            double weight
    ) {}
}
