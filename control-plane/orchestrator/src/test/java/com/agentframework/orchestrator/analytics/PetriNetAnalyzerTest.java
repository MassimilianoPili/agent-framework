package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PetriNetAnalyzer}.
 *
 * <p>Tests cover: reachable DAG, deadlocked DAG, empty input,
 * single-node DAG, and a diamond dependency shape.</p>
 */
class PetriNetAnalyzerTest {

    private PetriNetAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new PetriNetAnalyzer();
    }

    @Test
    @DisplayName("analyze returns null for empty edge list")
    void analyze_emptyEdges_returnsNull() {
        assertThat(analyzer.analyze(List.of())).isNull();
        assertThat(analyzer.analyze(null)).isNull();
    }

    @Test
    @DisplayName("linear DAG A→B→C is reachable and has no deadlock")
    void analyze_linearDag_reachableNoDeadlock() {
        // A must complete before B; B before C
        List<PetriNetAnalyzer.PlanItemEdge> edges = List.of(
                new PetriNetAnalyzer.PlanItemEdge("A", "B"),
                new PetriNetAnalyzer.PlanItemEdge("B", "C")
        );

        PetriNetAnalyzer.PetriNetReport report = analyzer.analyze(edges);

        assertThat(report).isNotNull();
        assertThat(report.reachable()).isTrue();
        assertThat(report.deadlockDetected()).isFalse();
        assertThat(report.deadlockedTasks()).isEmpty();
        assertThat(report.numPlaces()).isEqualTo(3);
        assertThat(report.numTransitions()).isEqualTo(2);
    }

    @Test
    @DisplayName("diamond DAG (A→B, A→C, B→D, C→D) is reachable")
    void analyze_diamondDag_reachable() {
        List<PetriNetAnalyzer.PlanItemEdge> edges = List.of(
                new PetriNetAnalyzer.PlanItemEdge("A", "B"),
                new PetriNetAnalyzer.PlanItemEdge("A", "C"),
                new PetriNetAnalyzer.PlanItemEdge("B", "D"),
                new PetriNetAnalyzer.PlanItemEdge("C", "D")
        );

        PetriNetAnalyzer.PetriNetReport report = analyzer.analyze(edges);

        assertThat(report).isNotNull();
        assertThat(report.reachable()).isTrue();
        assertThat(report.deadlockDetected()).isFalse();
        assertThat(report.numPlaces()).isEqualTo(4);
    }

    @Test
    @DisplayName("circular dependency A→B, B→A causes deadlock")
    void analyze_circularDependency_deadlockDetected() {
        // A waits for B, B waits for A — neither can fire from M₀
        List<PetriNetAnalyzer.PlanItemEdge> edges = List.of(
                new PetriNetAnalyzer.PlanItemEdge("A", "B"),
                new PetriNetAnalyzer.PlanItemEdge("B", "A")
        );

        PetriNetAnalyzer.PetriNetReport report = analyzer.analyze(edges);

        assertThat(report).isNotNull();
        assertThat(report.deadlockDetected()).isTrue();
        assertThat(report.deadlockedTasks()).containsExactlyInAnyOrder("A", "B");
        assertThat(report.reachable()).isFalse();
    }

    @Test
    @DisplayName("single edge A→B has both tasks as live transitions")
    void analyze_singleEdge_liveTransitions() {
        List<PetriNetAnalyzer.PlanItemEdge> edges = List.of(
                new PetriNetAnalyzer.PlanItemEdge("A", "B")
        );

        PetriNetAnalyzer.PetriNetReport report = analyzer.analyze(edges);

        assertThat(report).isNotNull();
        // A has no prerequisites → fires immediately; B fires after A
        assertThat(report.liveTransitions()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    @DisplayName("independent tasks (no edges between them) are all reachable")
    void analyze_independentTasks_allReachable() {
        // A→B and C→D are independent chains; they both reach DONE
        List<PetriNetAnalyzer.PlanItemEdge> edges = List.of(
                new PetriNetAnalyzer.PlanItemEdge("A", "B"),
                new PetriNetAnalyzer.PlanItemEdge("C", "D")
        );

        PetriNetAnalyzer.PetriNetReport report = analyzer.analyze(edges);

        assertThat(report).isNotNull();
        assertThat(report.reachable()).isTrue();
        assertThat(report.deadlockDetected()).isFalse();
        assertThat(report.numPlaces()).isEqualTo(4);
    }
}
