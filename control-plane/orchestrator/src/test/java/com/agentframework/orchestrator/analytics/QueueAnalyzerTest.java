package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.TropicalScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link QueueAnalyzer} — Erlang C, Little's Law, and per-plan queue analysis (#36).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueAnalyzer (#36) — Worker Pool Sizing")
class QueueAnalyzerTest {

    @Mock private QueuingCapacityPlanner queuingCapacityPlanner;
    @Mock private CriticalPathCalculator criticalPathCalculator;

    private QueueAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new QueueAnalyzer(queuingCapacityPlanner, criticalPathCalculator);
    }

    // ── Erlang C formula ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Erlang C formula")
    class ErlangCFormula {

        @Test
        @DisplayName("c=1, a=0.3 → low P(wait) ≈ 0.30")
        void erlangC_singleServer_lowLoad() {
            // M/M/1 with ρ = 0.3: P(wait) = ρ = 0.3 (Erlang C for c=1 reduces to ρ)
            double pWait = QueueAnalyzer.erlangC(1, 0.3);

            assertThat(pWait).isCloseTo(0.3, within(0.01));
        }

        @Test
        @DisplayName("c=1, a=0.9 → high P(wait) ≈ 0.90")
        void erlangC_singleServer_highLoad() {
            double pWait = QueueAnalyzer.erlangC(1, 0.9);

            assertThat(pWait).isCloseTo(0.9, within(0.01));
        }

        @Test
        @DisplayName("c=3 reduces P(wait) compared to c=1 at same offered load")
        void erlangC_multiServer_reducesWait() {
            double a = 2.0; // 2 Erlangs offered load
            double pWait1 = QueueAnalyzer.erlangC(1, a);  // unstable: ρ = 2 ≥ 1
            double pWait3 = QueueAnalyzer.erlangC(3, a);  // ρ = 2/3 < 1, stable

            assertThat(pWait1).isEqualTo(1.0); // unstable → P(wait) = 1
            assertThat(pWait3).isLessThan(1.0);
            assertThat(pWait3).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("a/c ≥ 1 → P(wait) = 1.0 (unstable)")
        void erlangC_unstable_returnsOne() {
            assertThat(QueueAnalyzer.erlangC(1, 1.0)).isEqualTo(1.0);
            assertThat(QueueAnalyzer.erlangC(2, 3.0)).isEqualTo(1.0);
            assertThat(QueueAnalyzer.erlangC(1, 5.0)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a=0 → P(wait) = 0 (no traffic)")
        void erlangC_noTraffic_returnsZero() {
            assertThat(QueueAnalyzer.erlangC(1, 0.0)).isEqualTo(0.0);
            assertThat(QueueAnalyzer.erlangC(5, 0.0)).isEqualTo(0.0);
        }
    }

    // ── Little's Law ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Little's Law")
    class LittleLaw {

        @Test
        @DisplayName("L = λ × W — exact formula")
        void littleL_matchesFormula() {
            double lambda = 0.002; // tasks/ms
            double waitMs = 5000;  // 5 seconds

            double L = QueueAnalyzer.littleLaw(lambda, waitMs);

            assertThat(L).isCloseTo(10.0, within(1e-9)); // 0.002 × 5000 = 10
        }

        @Test
        @DisplayName("λ = 0 → L = 0")
        void littleL_zeroArrival_zeroQueue() {
            assertThat(QueueAnalyzer.littleLaw(0, 5000)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("infinite wait → L = 0 (capped)")
        void littleL_infiniteWait_returnsZero() {
            assertThat(QueueAnalyzer.littleLaw(0.1, Double.POSITIVE_INFINITY)).isEqualTo(0.0);
        }
    }

    // ── Plan analysis ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plan analysis")
    class PlanAnalysis {

        @Test
        @DisplayName("single worker type: correct task count")
        void analyze_singleWorkerType_correctCounts() {
            Plan plan = planWith(
                    item("BE-001", WorkerType.BE, List.of()),
                    item("BE-002", WorkerType.BE, List.of("BE-001")),
                    item("BE-003", WorkerType.BE, List.of("BE-002")),
                    item("BE-004", WorkerType.BE, List.of()),
                    item("BE-005", WorkerType.BE, List.of())
            );
            stubCpm(plan, 1_500_000, List.of("BE-001", "BE-002", "BE-003"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            assertThat(result.byWorkerType()).containsKey("BE");
            assertThat(result.byWorkerType().get("BE").taskCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("multiple worker types: each analysed")
        void analyze_multipleWorkerTypes_eachAnalyzed() {
            Plan plan = planWith(
                    item("BE-001", WorkerType.BE, List.of()),
                    item("FE-001", WorkerType.FE, List.of()),
                    item("CM-001", WorkerType.CONTEXT_MANAGER, List.of())
            );
            stubCpm(plan, 900_000, List.of("BE-001"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            assertThat(result.byWorkerType()).containsKeys("BE", "FE", "CONTEXT_MANAGER");
            assertThat(result.byWorkerType().get("BE").taskCount()).isEqualTo(1);
            assertThat(result.byWorkerType().get("FE").taskCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("no historical data → uses default 5 min service time")
        void analyze_noHistoricalData_usesDefault() {
            Plan plan = planWith(item("BE-001", WorkerType.BE, List.of()));
            stubCpm(plan, 300_000, List.of("BE-001"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            assertThat(result.byWorkerType().get("BE").meanServiceTimeMs())
                    .isEqualTo(QueueAnalyzer.DEFAULT_SERVICE_TIME_MS);
        }

        @Test
        @DisplayName("bottleneck = worker type with highest wait")
        void analyze_bottleneck_identifiesHighestWait() {
            // BE: 5 tasks (high contention), FE: 1 task (low contention)
            Plan plan = planWith(
                    item("BE-001", WorkerType.BE, List.of()),
                    item("BE-002", WorkerType.BE, List.of()),
                    item("BE-003", WorkerType.BE, List.of()),
                    item("BE-004", WorkerType.BE, List.of()),
                    item("BE-005", WorkerType.BE, List.of()),
                    item("FE-001", WorkerType.FE, List.of())
            );
            stubCpm(plan, 1_500_000, List.of("BE-001"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            // BE has higher traffic intensity (5 tasks vs 1) → higher wait → bottleneck
            assertThat(result.bottleneckWorkerType()).isEqualTo("BE");
        }

        @Test
        @DisplayName("empty plan → empty result")
        void analyze_emptyPlan_emptyResult() {
            Plan plan = new Plan(UUID.randomUUID(), "empty");

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            assertThat(result.byWorkerType()).isEmpty();
            assertThat(result.makespanMs()).isEqualTo(0);
            assertThat(result.criticalPath()).isEmpty();
            assertThat(result.bottleneckWorkerType()).isNull();
        }
    }

    // ── Integration with CPM ────────────────────────────────────────────────

    @Nested
    @DisplayName("CPM integration")
    class CpmIntegration {

        @Test
        @DisplayName("makespan comes from CriticalPathCalculator")
        void analyze_makespan_fromCriticalPath() {
            Plan plan = planWith(item("BE-001", WorkerType.BE, List.of()));
            double expectedMakespan = 600_000;
            stubCpm(plan, expectedMakespan, List.of("BE-001"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            assertThat(result.makespanMs()).isEqualTo(expectedMakespan);
        }

        @Test
        @DisplayName("critical path is forwarded from CPM")
        void analyze_criticalPath_passedThrough() {
            Plan plan = planWith(
                    item("A", WorkerType.BE, List.of()),
                    item("B", WorkerType.BE, List.of("A")),
                    item("C", WorkerType.FE, List.of())
            );
            stubCpm(plan, 600_000, List.of("A", "B"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            assertThat(result.criticalPath()).containsExactly("A", "B");
        }

        @Test
        @DisplayName("recommended consumers reduce P(wait)")
        void analyze_recommendedConsumers_reducesWait() {
            // High traffic: 10 BE tasks in 300s makespan with 300s service time
            // → a = 10 Erlangs, c=1 unstable
            Plan plan = planWith(
                    item("BE-001", WorkerType.BE, List.of()),
                    item("BE-002", WorkerType.BE, List.of()),
                    item("BE-003", WorkerType.BE, List.of()),
                    item("BE-004", WorkerType.BE, List.of()),
                    item("BE-005", WorkerType.BE, List.of()),
                    item("BE-006", WorkerType.BE, List.of()),
                    item("BE-007", WorkerType.BE, List.of()),
                    item("BE-008", WorkerType.BE, List.of()),
                    item("BE-009", WorkerType.BE, List.of()),
                    item("BE-010", WorkerType.BE, List.of())
            );
            stubCpm(plan, 300_000, List.of("BE-001"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            QueueAnalyzer.WorkerTypeAnalysis be = result.byWorkerType().get("BE");
            // With recommended consumers, P(wait) should be below threshold
            int rec = be.recommendedConsumers();
            double pWaitRec = QueueAnalyzer.erlangC(rec, be.arrivalRatePerMs() * be.meanServiceTimeMs());
            assertThat(pWaitRec).isLessThan(QueueAnalyzer.ERLANG_C_THRESHOLD);
        }

        @Test
        @DisplayName("saturated flag when ρ ≥ 0.90")
        void analyze_saturatedFlag_whenHighLoad() {
            // 9 tasks with 300s service time in 300s makespan → a ≈ 9, ρ = 9 (c=1) → saturated
            Plan plan = planWith(
                    item("BE-001", WorkerType.BE, List.of()),
                    item("BE-002", WorkerType.BE, List.of()),
                    item("BE-003", WorkerType.BE, List.of()),
                    item("BE-004", WorkerType.BE, List.of()),
                    item("BE-005", WorkerType.BE, List.of()),
                    item("BE-006", WorkerType.BE, List.of()),
                    item("BE-007", WorkerType.BE, List.of()),
                    item("BE-008", WorkerType.BE, List.of()),
                    item("BE-009", WorkerType.BE, List.of())
            );
            stubCpm(plan, 300_000, List.of("BE-001"));
            stubNoHistoricalData();

            QueueAnalyzer.QueueAnalysisResult result = analyzer.analyze(plan);

            assertThat(result.byWorkerType().get("BE").saturated()).isTrue();
        }
    }

    // ── Test helpers ────────────────────────────────────────────────────────

    private Plan planWith(PlanItem... items) {
        Plan plan = new Plan(UUID.randomUUID(), "test-plan");
        for (PlanItem item : items) {
            plan.addItem(item);
        }
        return plan;
    }

    private PlanItem item(String taskKey, WorkerType type, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, "Title " + taskKey,
                "Desc", type, type.name().toLowerCase(), deps, List.of());
    }

    private void stubCpm(Plan plan, double makespanMs, List<String> criticalPath) {
        TropicalScheduler.ScheduleResult schedule = new TropicalScheduler.ScheduleResult(
                Map.of(), Map.of(), Map.of(), criticalPath, makespanMs
        );
        when(criticalPathCalculator.computeSchedule(any(Plan.class))).thenReturn(schedule);
    }

    private void stubNoHistoricalData() {
        when(queuingCapacityPlanner.analyze(anyString())).thenReturn(null);
    }
}
