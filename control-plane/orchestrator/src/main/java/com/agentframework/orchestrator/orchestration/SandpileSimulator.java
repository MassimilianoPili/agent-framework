package com.agentframework.orchestrator.orchestration;

import java.util.*;

/**
 * Pure mathematical implementation of the Bak-Tang-Wiesenfeld sandpile model
 * for Self-Organized Criticality (SOC) analysis.
 *
 * <p>Each WorkerType is a node in the sandpile lattice. A node "topples" when
 * its load exceeds its threshold, spilling a fraction of the threshold to each
 * neighbour. The cascade continues until all nodes are below threshold or the
 * maximum iteration count is reached.</p>
 *
 * <p>Load formula (computed externally by {@link CriticalityMonitor}):
 * <pre>
 *   load[wt] = pending_count + failed_count * 2 + stale_count * 1.5
 * </pre>
 *
 * <p>Criticality index:
 * <pre>
 *   C = max_wt(load[wt] / threshold[wt])
 *   C &lt; 0.5 → STABLE, 0.5–0.8 → WARNING, ≥ 0.8 → ALERT
 * </pre>
 *
 * <p>This class is stateless per invocation — create a new instance per evaluation cycle.
 *
 * @see <a href="https://doi.org/10.1103/PhysRevLett.59.381">
 *     Bak, Tang &amp; Wiesenfeld (1987), Physical Review Letters</a>
 */
public class SandpileSimulator {

    /** Fraction of threshold spilled to each neighbour during topple. */
    public static final double SPILLOVER_RATIO = 0.3;

    /** Maximum topple iterations before halting (prevents infinite loops in cyclic graphs). */
    public static final int MAX_TOPPLE_ITERATIONS = 50;

    private final Map<String, Double> loads;
    private final Map<String, Double> thresholds;
    private final Map<String, List<String>> neighbours;

    /**
     * @param loads      current load per node (mutated during stabilisation)
     * @param thresholds topple threshold per node
     * @param neighbours adjacency list: node → list of neighbour nodes
     */
    public SandpileSimulator(Map<String, Double> loads,
                              Map<String, Double> thresholds,
                              Map<String, List<String>> neighbours) {
        this.loads      = new HashMap<>(loads);
        this.thresholds = new HashMap<>(thresholds);
        this.neighbours = new HashMap<>(neighbours);
    }

    /**
     * Runs the topple cascade until stable or {@link #MAX_TOPPLE_ITERATIONS} reached.
     *
     * @return ordered list of nodes that toppled during stabilisation
     */
    public List<String> stabilise() {
        List<String> toppled = new ArrayList<>();
        for (int i = 0; i < MAX_TOPPLE_ITERATIONS; i++) {
            String unstable = findUnstableNode();
            if (unstable == null) break;
            topple(unstable);
            toppled.add(unstable);
        }
        return toppled;
    }

    /**
     * Computes criticality index: C = max_wt(load[wt] / threshold[wt]).
     *
     * @return criticality index (0.0 if no nodes)
     */
    public double criticalityIndex() {
        return loads.entrySet().stream()
                .filter(e -> thresholds.containsKey(e.getKey())
                          && thresholds.get(e.getKey()) > 0)
                .mapToDouble(e -> e.getValue() / thresholds.get(e.getKey()))
                .max()
                .orElse(0.0);
    }

    /** Returns an unmodifiable copy of the current load map (after stabilisation). */
    public Map<String, Double> getLoads() {
        return Collections.unmodifiableMap(loads);
    }

    // ── private ─────────────────────────────────────────────────────────────

    private String findUnstableNode() {
        return loads.entrySet().stream()
                .filter(e -> thresholds.containsKey(e.getKey())
                          && e.getValue() > thresholds.get(e.getKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void topple(String node) {
        double threshold = thresholds.get(node);
        loads.put(node, loads.get(node) - threshold);

        List<String> nbrs = neighbours.getOrDefault(node, Collections.emptyList());
        if (nbrs.isEmpty()) return;

        double spilloverPerNeighbour = (SPILLOVER_RATIO * threshold) / nbrs.size();
        for (String nbr : nbrs) {
            loads.merge(nbr, spilloverPerNeighbour, Double::sum);
        }
    }
}
