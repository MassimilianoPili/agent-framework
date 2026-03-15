package com.agentframework.orchestrator.visualization;

import com.agentframework.orchestrator.visualization.PlanGraphModel.GraphEdge;
import com.agentframework.orchestrator.visualization.PlanGraphModel.GraphNode;

import java.util.*;

/**
 * Sugiyama-style layered graph layout for DAG visualization.
 *
 * <p>Three-phase algorithm:</p>
 * <ol>
 *   <li><b>Layer assignment</b>: topological sort via Kahn's algorithm,
 *       longest-path layer = max(predecessors) + 1</li>
 *   <li><b>Crossing minimization</b>: barycenter heuristic (2-pass,
 *       forward then backward)</li>
 *   <li><b>Coordinate assignment</b>: layer → x, position-in-layer → y,
 *       with vertical centering</li>
 * </ol>
 *
 * <p>This is a pure POJO — no Spring dependencies — for easy unit testing.</p>
 *
 * @see <a href="https://doi.org/10.1109/TSMC.1981.4308636">Sugiyama et al. (1981)</a>
 */
public class GraphLayoutEngine {

    private final int horizontalSpacing;
    private final int verticalSpacing;

    public GraphLayoutEngine(int horizontalSpacing, int verticalSpacing) {
        this.horizontalSpacing = horizontalSpacing;
        this.verticalSpacing = verticalSpacing;
    }

    /**
     * Applies layout to a graph model, returning a new model with coordinates.
     *
     * @param model graph model with layer/x/y = 0
     * @return new model with computed coordinates
     */
    public PlanGraphModel applyLayout(PlanGraphModel model) {
        if (model.nodes().isEmpty()) return model;

        // Build adjacency structures
        Map<String, Set<String>> predecessors = new LinkedHashMap<>();
        Map<String, Set<String>> successors = new LinkedHashMap<>();
        for (GraphNode node : model.nodes()) {
            predecessors.put(node.id(), new LinkedHashSet<>());
            successors.put(node.id(), new LinkedHashSet<>());
        }
        for (GraphEdge edge : model.edges()) {
            if (predecessors.containsKey(edge.target()) && successors.containsKey(edge.source())) {
                predecessors.get(edge.target()).add(edge.source());
                successors.get(edge.source()).add(edge.target());
            }
        }

        // Phase 1: Layer assignment (longest path)
        Map<String, Integer> layers = assignLayers(model.nodes(), predecessors);

        // Phase 2: Crossing minimization (barycenter)
        Map<String, Integer> positions = minimizeCrossings(layers, predecessors, successors);

        // Phase 3: Coordinate assignment
        int maxLayer = layers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<Integer, Integer> nodesPerLayer = new HashMap<>();
        for (int l : layers.values()) {
            nodesPerLayer.merge(l, 1, Integer::sum);
        }

        List<GraphNode> layoutNodes = new ArrayList<>();
        for (GraphNode node : model.nodes()) {
            int layer = layers.getOrDefault(node.id(), 0);
            int posInLayer = positions.getOrDefault(node.id(), 0);
            int layerSize = nodesPerLayer.getOrDefault(layer, 1);

            double x = layer * horizontalSpacing;
            // Center vertically
            double totalHeight = (layerSize - 1) * verticalSpacing;
            double y = posInLayer * verticalSpacing - totalHeight / 2.0;

            layoutNodes.add(new GraphNode(
                    node.id(), node.label(), node.type(), node.status(),
                    node.group(), layer, x, y, node.metadata()));
        }

        return new PlanGraphModel(model.planId(), model.status(), layoutNodes, model.edges(), model.metadata());
    }

    /**
     * Phase 1: Longest-path layer assignment using Kahn's topological sort.
     */
    Map<String, Integer> assignLayers(List<GraphNode> nodes, Map<String, Set<String>> predecessors) {
        Map<String, Integer> layers = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (GraphNode node : nodes) {
            inDegree.put(node.id(), predecessors.getOrDefault(node.id(), Set.of()).size());
        }

        // Start with nodes having no predecessors
        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
                layers.put(entry.getKey(), 0);
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentLayer = layers.getOrDefault(current, 0);

            // Find successors
            for (GraphNode node : nodes) {
                Set<String> preds = predecessors.getOrDefault(node.id(), Set.of());
                if (preds.contains(current)) {
                    // Longest path: layer = max(predecessors' layer + 1)
                    int newLayer = currentLayer + 1;
                    layers.merge(node.id(), newLayer, Math::max);

                    int remaining = inDegree.get(node.id()) - 1;
                    inDegree.put(node.id(), remaining);
                    if (remaining == 0) {
                        queue.add(node.id());
                    }
                }
            }
        }

        // Handle nodes not reached (cycles or disconnected) — place at layer 0
        for (GraphNode node : nodes) {
            layers.putIfAbsent(node.id(), 0);
        }

        return layers;
    }

    /**
     * Phase 2: Barycenter heuristic for crossing minimization.
     *
     * <p>Two-pass: forward (layer 0→max) using predecessor barycenters,
     * then backward (max→0) using successor barycenters.</p>
     */
    Map<String, Integer> minimizeCrossings(Map<String, Integer> layers,
                                            Map<String, Set<String>> predecessors,
                                            Map<String, Set<String>> successors) {
        // Group nodes by layer
        int maxLayer = layers.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<Integer, List<String>> layerNodes = new TreeMap<>();
        for (var entry : layers.entrySet()) {
            layerNodes.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        // Initial positions: order of appearance
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (List<String> nodesInLayer : layerNodes.values()) {
            for (int i = 0; i < nodesInLayer.size(); i++) {
                positions.put(nodesInLayer.get(i), i);
            }
        }

        // Forward pass: sort by predecessor barycenter
        for (int layer = 1; layer <= maxLayer; layer++) {
            List<String> current = layerNodes.getOrDefault(layer, List.of());
            if (current.size() <= 1) continue;

            current.sort(Comparator.comparingDouble(nodeId -> {
                Set<String> preds = predecessors.getOrDefault(nodeId, Set.of());
                if (preds.isEmpty()) return 0.0;
                return preds.stream()
                        .mapToDouble(p -> positions.getOrDefault(p, 0))
                        .average().orElse(0);
            }));

            for (int i = 0; i < current.size(); i++) {
                positions.put(current.get(i), i);
            }
        }

        // Backward pass: sort by successor barycenter
        for (int layer = maxLayer - 1; layer >= 0; layer--) {
            List<String> current = layerNodes.getOrDefault(layer, List.of());
            if (current.size() <= 1) continue;

            current.sort(Comparator.comparingDouble(nodeId -> {
                Set<String> succs = successors.getOrDefault(nodeId, Set.of());
                if (succs.isEmpty()) return 0.0;
                return succs.stream()
                        .mapToDouble(s -> positions.getOrDefault(s, 0))
                        .average().orElse(0);
            }));

            for (int i = 0; i < current.size(); i++) {
                positions.put(current.get(i), i);
            }
        }

        return positions;
    }
}
