package com.agentframework.orchestrator.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link SandpileSimulator} — pure mathematical sandpile model.
 */
class SandpileSimulatorTest {

    @Test
    void stabilise_noUnstableNodes_emptyToppledList() {
        Map<String, Double> loads = Map.of("A", 5.0, "B", 3.0);
        Map<String, Double> thresholds = Map.of("A", 15.0, "B", 15.0);
        Map<String, List<String>> neighbours = Map.of("A", List.of("B"), "B", List.of("A"));

        SandpileSimulator sim = new SandpileSimulator(loads, thresholds, neighbours);
        List<String> toppled = sim.stabilise();

        assertThat(toppled).isEmpty();
        assertThat(sim.criticalityIndex()).isCloseTo(5.0 / 15.0, within(0.001));
    }

    @Test
    void criticalityIndex_computesMaxRatio() {
        // A: load 12 / threshold 15 = 0.8, B: load 3 / threshold 15 = 0.2
        Map<String, Double> loads = Map.of("A", 12.0, "B", 3.0);
        Map<String, Double> thresholds = Map.of("A", 15.0, "B", 15.0);
        Map<String, List<String>> neighbours = Map.of();

        SandpileSimulator sim = new SandpileSimulator(loads, thresholds, neighbours);

        assertThat(sim.criticalityIndex()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void stabilise_unstableNode_topplesAndSpillsToNeighbours() {
        // A: load 20 > threshold 15 → topple. A has 2 neighbours B and C.
        // After topple: A = 20 - 15 = 5, spillover per neighbour = 0.3 * 15 / 2 = 2.25
        // B = 0 + 2.25 = 2.25, C = 0 + 2.25 = 2.25
        Map<String, Double> loads = Map.of("A", 20.0, "B", 0.0, "C", 0.0);
        Map<String, Double> thresholds = Map.of("A", 15.0, "B", 15.0, "C", 15.0);
        Map<String, List<String>> neighbours = Map.of(
                "A", List.of("B", "C"),
                "B", List.of("A"),
                "C", List.of("A"));

        SandpileSimulator sim = new SandpileSimulator(loads, thresholds, neighbours);
        List<String> toppled = sim.stabilise();

        assertThat(toppled).containsExactly("A");
        assertThat(sim.getLoads().get("A")).isCloseTo(5.0, within(0.001));
        assertThat(sim.getLoads().get("B")).isCloseTo(2.25, within(0.001));
        assertThat(sim.getLoads().get("C")).isCloseTo(2.25, within(0.001));
    }

    @Test
    void stabilise_cascadeTopple_multipleNodesTopple() {
        // A: load 20 > threshold 15 → topples, spills to B
        // B: load 14 + spillover(0.3 * 15 / 1 = 4.5) = 18.5 > 15 → topples too
        Map<String, Double> loads = Map.of("A", 20.0, "B", 14.0);
        Map<String, Double> thresholds = Map.of("A", 15.0, "B", 15.0);
        Map<String, List<String>> neighbours = Map.of(
                "A", List.of("B"),
                "B", List.of());

        SandpileSimulator sim = new SandpileSimulator(loads, thresholds, neighbours);
        List<String> toppled = sim.stabilise();

        assertThat(toppled).containsExactly("A", "B");
        assertThat(sim.getLoads().get("A")).isCloseTo(5.0, within(0.001));
        // B: 14 + 4.5 - 15 = 3.5 (no neighbours to spill to)
        assertThat(sim.getLoads().get("B")).isCloseTo(3.5, within(0.001));
    }

    @Test
    void customSpilloverRatio_higherSpilloverIncreasesNeighbourLoad() {
        // A: load 20 > threshold 15 → topple. A has 1 neighbour B.
        // spilloverRatio = 0.6 → spillover = 0.6 * 15 / 1 = 9.0
        // B: 0 + 9.0 = 9.0
        Map<String, Double> loads = Map.of("A", 20.0, "B", 0.0);
        Map<String, Double> thresholds = Map.of("A", 15.0, "B", 15.0);
        Map<String, List<String>> neighbours = Map.of(
                "A", List.of("B"),
                "B", List.of());

        SandpileSimulator sim = new SandpileSimulator(loads, thresholds, neighbours, 0.6, 50);
        List<String> toppled = sim.stabilise();

        assertThat(toppled).containsExactly("A");
        assertThat(sim.getLoads().get("A")).isCloseTo(5.0, within(0.001));
        assertThat(sim.getLoads().get("B")).isCloseTo(9.0, within(0.001));
    }

    @Test
    void maxToppleIterations_limitsCascade() {
        // A and B form a cycle. Both start above threshold.
        // With maxToppleIterations=2, cascade halts after 2 topples.
        Map<String, Double> loads = Map.of("A", 20.0, "B", 20.0);
        Map<String, Double> thresholds = Map.of("A", 15.0, "B", 15.0);
        Map<String, List<String>> neighbours = Map.of(
                "A", List.of("B"),
                "B", List.of("A"));

        SandpileSimulator sim = new SandpileSimulator(loads, thresholds, neighbours, 0.3, 2);
        List<String> toppled = sim.stabilise();

        assertThat(toppled).hasSize(2);
    }
}
