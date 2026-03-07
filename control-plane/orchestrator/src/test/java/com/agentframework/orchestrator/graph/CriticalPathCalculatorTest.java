package com.agentframework.orchestrator.graph;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link CriticalPathCalculator} — domain bridge to {@link TropicalScheduler}.
 */
class CriticalPathCalculatorTest {

    private final CriticalPathCalculator calculator = new CriticalPathCalculator();

    @Test
    void linearChain_allNodesOnCriticalPath() {
        // A → B → C (linear chain, all on critical path)
        Plan plan = new Plan(UUID.randomUUID(), "test");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of("B")));

        var result = calculator.computeSchedule(plan);

        assertThat(result.criticalPath()).containsExactly("A", "B", "C");
        assertThat(result.makespanMs()).isCloseTo(
                CriticalPathCalculator.DEFAULT_ESTIMATED_MS * 3, within(1.0));
    }

    @Test
    void parallelPaths_longerPathIsCritical() {
        // A → B (path 1: 2 tasks)
        // A → C → D (path 2: 3 tasks, longer)
        Plan plan = new Plan(UUID.randomUUID(), "test");
        plan.addItem(item("A", List.of()));
        plan.addItem(item("B", List.of("A")));
        plan.addItem(item("C", List.of("A")));
        plan.addItem(item("D", List.of("C")));

        var result = calculator.computeSchedule(plan);

        assertThat(result.criticalPath()).contains("A", "C", "D");
        assertThat(result.criticalPath()).doesNotContain("B");
    }

    @Test
    void doneItems_useActualDuration() {
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end   = Instant.parse("2025-01-01T00:01:00Z"); // 60 seconds = 60000 ms

        Plan plan = new Plan(UUID.randomUUID(), "test");
        PlanItem doneItem = item("A", List.of());
        doneItem.transitionTo(ItemStatus.DISPATCHED);
        doneItem.setDispatchedAt(start);
        doneItem.transitionTo(ItemStatus.DONE);
        doneItem.setCompletedAt(end);
        plan.addItem(doneItem);

        double duration = calculator.extractDurationMs(doneItem);

        assertThat(duration).isCloseTo(60_000.0, within(1.0));
    }

    @Test
    void waitingItems_useDefaultEstimate() {
        PlanItem waitingItem = item("A", List.of());

        double duration = calculator.extractDurationMs(waitingItem);

        assertThat(duration).isEqualTo(CriticalPathCalculator.DEFAULT_ESTIMATED_MS);
    }

    @Test
    void emptyPlan_zeroMakespan() {
        Plan plan = new Plan(UUID.randomUUID(), "empty");

        var view = calculator.buildView(plan);

        assertThat(view.makespanMs()).isEqualTo(0.0);
        assertThat(view.criticalPath()).isEmpty();
        assertThat(view.nodes()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PlanItem item(String taskKey, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, "Title " + taskKey,
                "Desc", WorkerType.BE, "be-java", deps);
    }
}
