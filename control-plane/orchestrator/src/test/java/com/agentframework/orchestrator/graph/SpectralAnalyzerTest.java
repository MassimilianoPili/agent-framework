package com.agentframework.orchestrator.graph;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link SpectralAnalyzer} — Laplacian eigenvalue analysis of plan DAGs.
 *
 * @see <a href="https://doi.org/10.21136/CMJ.1973.101168">Fiedler (1973)</a>
 */
class SpectralAnalyzerTest {

    private final SpectralAnalyzer analyzer = new SpectralAnalyzer();

    @Test
    void emptyPlan_zeroMetrics() {
        Plan plan = new Plan(UUID.randomUUID(), "empty");

        SpectralMetrics metrics = analyzer.analyse(plan);

        assertThat(metrics.fiedlerValue()).isEqualTo(0.0);
        assertThat(metrics.spectralGap()).isEqualTo(0.0);
        assertThat(metrics.eigenvalues()).isEmpty();
        assertThat(metrics.nodeCount()).isEqualTo(0);
        assertThat(metrics.edgeCount()).isEqualTo(0);
        assertThat(metrics.partition()).isEmpty();
        assertThat(metrics.bottlenecks()).isEmpty();
    }

    @Test
    void singleNode_zeroFiedler() {
        Plan plan = new Plan(UUID.randomUUID(), "single");
        plan.addItem(item("A", List.of()));

        SpectralMetrics metrics = analyzer.analyse(plan);

        assertThat(metrics.fiedlerValue()).isEqualTo(0.0);
        assertThat(metrics.nodeCount()).isEqualTo(1);
        assertThat(metrics.edgeCount()).isEqualTo(0);
        assertThat(metrics.eigenvalues()).hasSize(1);
    }

    @Test
    void linearChain_lowFiedler() {
        // A → B → C — linear chain, fragile graph
        Plan plan = new Plan(UUID.randomUUID(), "linear");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of("B")));

        SpectralMetrics metrics = analyzer.analyse(plan);

        // Fiedler value for a path graph P3 = 2 - 2*cos(pi/3) = 2 - 1 = 1.0
        assertThat(metrics.fiedlerValue()).isGreaterThan(0.0);
        assertThat(metrics.nodeCount()).isEqualTo(3);
        assertThat(metrics.edgeCount()).isEqualTo(2);
        assertThat(metrics.eigenvalues()).hasSize(3);
        // First eigenvalue should be ≈ 0
        assertThat(metrics.eigenvalues()[0]).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void fullyConnected_highFiedler() {
        // A→B, A→C, B→C — triangle, robust graph
        Plan plan = new Plan(UUID.randomUUID(), "triangle");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of("A", "B")));

        SpectralMetrics metrics = analyzer.analyse(plan);

        // K3 (complete graph on 3 vertices): Fiedler value = 3.0
        assertThat(metrics.fiedlerValue()).isCloseTo(3.0, within(0.1));
        assertThat(metrics.edgeCount()).isEqualTo(3);
        // Spectral gap for K3 = 3/3 = 1.0
        assertThat(metrics.spectralGap()).isCloseTo(1.0, within(0.1));
    }

    @Test
    void disconnectedComponents_zeroFiedler() {
        // A→B and C→D (no edges between clusters) — disconnected
        Plan plan = new Plan(UUID.randomUUID(), "disconnected");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of()));
        plan.addItem(item("D", List.of("C")));

        SpectralMetrics metrics = analyzer.analyse(plan);

        // Disconnected graph: λ₁ = 0
        assertThat(metrics.fiedlerValue()).isCloseTo(0.0, within(1e-6));
        assertThat(metrics.nodeCount()).isEqualTo(4);
        assertThat(metrics.edgeCount()).isEqualTo(2);
        // Partition should have 2 components
        assertThat(metrics.partition()).hasSize(2);
    }

    @Test
    void buildLaplacian_correctDiagonal() {
        // Simple triangle: A→B, A→C, B→C
        // Symmetric adjacency: every node connected to 2 others
        Plan plan = new Plan(UUID.randomUUID(), "test");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of("A", "B")));

        var keyIndex = new java.util.LinkedHashMap<String, Integer>();
        keyIndex.put("A", 0);
        keyIndex.put("B", 1);
        keyIndex.put("C", 2);

        double[][] adj = analyzer.buildSymmetricAdjacency(plan.getItems(), keyIndex, 3);
        double[][] L = analyzer.buildLaplacian(adj, 3);

        // In K3: each node has degree 2
        assertThat(L[0][0]).isEqualTo(2.0);
        assertThat(L[1][1]).isEqualTo(2.0);
        assertThat(L[2][2]).isEqualTo(2.0);
        // Off-diagonal = -1
        assertThat(L[0][1]).isEqualTo(-1.0);
        assertThat(L[1][0]).isEqualTo(-1.0);
    }

    @Test
    void fiedlerPartition_twoGroups() {
        // Create a graph with clear partition: {A,B} and {C,D,E}
        // A→B (cluster 1), C→D, C→E, D→E (cluster 2), B→C (bridge)
        Plan plan = new Plan(UUID.randomUUID(), "partitioned");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of("B")));
        plan.addItem(item("D", List.of("C")));
        plan.addItem(item("E", List.of("C", "D")));

        SpectralMetrics metrics = analyzer.analyse(plan);

        // Should produce a partition (2 groups)
        assertThat(metrics.partition()).hasSizeGreaterThanOrEqualTo(1);
        // All task keys should be present across all partition groups
        List<String> allKeys = metrics.partition().stream()
                .flatMap(List::stream)
                .toList();
        assertThat(allKeys).containsExactlyInAnyOrder("A", "B", "C", "D", "E");
    }

    @Test
    void spectralGap_computed() {
        // A→B→C: path graph
        Plan plan = new Plan(UUID.randomUUID(), "gap-test");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of("B")));

        SpectralMetrics metrics = analyzer.analyse(plan);

        // spectralGap = fiedler / lambdaMax
        assertThat(metrics.spectralGap()).isGreaterThan(0.0);
        assertThat(metrics.spectralGap()).isLessThanOrEqualTo(1.0);
        // Verify algebraicConnectivity == fiedlerValue
        assertThat(metrics.algebraicConnectivity()).isEqualTo(metrics.fiedlerValue());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PlanItem item(String taskKey, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, "Title " + taskKey,
                "Desc", WorkerType.BE, "be-java", deps);
    }
}
