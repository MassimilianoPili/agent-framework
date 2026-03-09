package com.agentframework.orchestrator.assignment;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.config.GlobalAssignmentProperties;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.TropicalScheduler;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GlobalAssignmentSolver} — global task-to-profile
 * assignment via Hungarian Algorithm (#42).
 */
@ExtendWith(MockitoExtension.class)
class GlobalAssignmentSolverTest {

    @Mock private TaskOutcomeService taskOutcomeService;
    @Mock private WorkerProfileRegistry profileRegistry;
    @Mock private CriticalPathCalculator criticalPathCalculator;
    @Mock private Plan plan;

    private GlobalAssignmentSolver solver;

    private static final float[] DUMMY_EMBEDDING = new float[]{0.1f, 0.2f, 0.3f};

    @BeforeEach
    void setUp() {
        GlobalAssignmentProperties props = new GlobalAssignmentProperties(true, 0.2, 2);
        solver = new GlobalAssignmentSolver(taskOutcomeService, profileRegistry,
                criticalPathCalculator, props);

        // Default: critical path computation returns empty (no CP tasks)
        // Lenient: tests that override this would otherwise trigger UnnecessaryStubbing
        lenient().when(criticalPathCalculator.computeSchedule(any(Plan.class)))
                .thenReturn(new TropicalScheduler.ScheduleResult(
                        Map.of(), Map.of(), Map.of(), List.of(), 0.0));
    }

    @Test
    void twoBeTasksDifferentPredictions_globalOptimalNotGreedy() {
        // Setup: 2 BE tasks, 2 BE profiles
        // Greedy: both would pick be-java (higher mu for each)
        // Global: task0 → be-java, task1 → be-go (globally optimal)
        PlanItem task0 = makeItem("BE-001", WorkerType.BE, "Implement API");
        PlanItem task1 = makeItem("BE-002", WorkerType.BE, "Implement DB layer");

        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(DUMMY_EMBEDDING);

        // task0: be-java=0.9, be-go=0.5
        // task1: be-java=0.85, be-go=0.7
        // Greedy: both pick be-java → conflict
        // Global optimal: task0→be-java(0.9) + task1→be-go(0.7) = 1.6
        // vs task0→be-go(0.5) + task1→be-java(0.85) = 1.35
        when(taskOutcomeService.predict(any(), eq("BE"), eq("be-java")))
                .thenReturn(new GpPrediction(0.9, 0.01))
                .thenReturn(new GpPrediction(0.85, 0.02));
        when(taskOutcomeService.predict(any(), eq("BE"), eq("be-go")))
                .thenReturn(new GpPrediction(0.5, 0.03))
                .thenReturn(new GpPrediction(0.7, 0.02));

        AssignmentResult result = solver.solve(List.of(task0, task1), plan);

        assertThat(result.assignments()).hasSize(2);
        // The two tasks must be assigned to different profiles
        assertThat(result.assignments().get("BE-001"))
                .isNotEqualTo(result.assignments().get("BE-002"));
        // Global optimal: total mu should be maximized (= total cost minimized)
        // Best: BE-001→be-java(0.9), BE-002→be-go(0.7) → totalCost = -0.9 + -0.7 = -1.6
        assertThat(result.totalCost()).isLessThan(-1.3);
    }

    @Test
    void mixedTypes_beAndFe_incompatibleExcluded() {
        PlanItem beTask = makeItem("BE-001", WorkerType.BE, "Backend task");
        PlanItem feTask = makeItem("FE-001", WorkerType.FE, "Frontend task");

        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java"));
        when(profileRegistry.profilesForWorkerType(WorkerType.FE))
                .thenReturn(List.of("fe-react"));

        when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(DUMMY_EMBEDDING);

        when(taskOutcomeService.predict(any(), eq("BE"), eq("be-java")))
                .thenReturn(new GpPrediction(0.8, 0.01));
        when(taskOutcomeService.predict(any(), eq("FE"), eq("fe-react")))
                .thenReturn(new GpPrediction(0.75, 0.02));

        AssignmentResult result = solver.solve(List.of(beTask, feTask), plan);

        assertThat(result.assignments()).hasSize(2);
        assertThat(result.assignments().get("BE-001")).isEqualTo("be-java");
        assertThat(result.assignments().get("FE-001")).isEqualTo("fe-react");
    }

    @Test
    void allOnCriticalPath_boostApplied() {
        PlanItem task0 = makeItem("BE-001", WorkerType.BE, "Critical task");
        PlanItem task1 = makeItem("BE-002", WorkerType.BE, "Another critical");

        // Both tasks on critical path
        when(criticalPathCalculator.computeSchedule(any(Plan.class)))
                .thenReturn(new TropicalScheduler.ScheduleResult(
                        Map.of(), Map.of(), Map.of(),
                        List.of("BE-001", "BE-002"), 600_000.0));

        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(DUMMY_EMBEDDING);

        when(taskOutcomeService.predict(any(), eq("BE"), eq("be-java")))
                .thenReturn(new GpPrediction(0.8, 0.01))
                .thenReturn(new GpPrediction(0.6, 0.02));
        when(taskOutcomeService.predict(any(), eq("BE"), eq("be-go")))
                .thenReturn(new GpPrediction(0.5, 0.03))
                .thenReturn(new GpPrediction(0.7, 0.02));

        AssignmentResult result = solver.solve(List.of(task0, task1), plan);

        // All details should have boosted=true
        assertThat(result.details()).allMatch(d -> d.boosted());
        assertThat(result.details()).allMatch(d -> d.onCriticalPath());
        assertThat(result.criticalPath()).containsExactlyInAnyOrder("BE-001", "BE-002");
    }

    @Test
    void cpBoostOverridesGreedy_cpTaskGetsBestProfile() {
        // Without boost: task0 and task1 have similar mu for be-java
        // With boost on task1 (CP), task1 gets priority for be-java
        PlanItem task0 = makeItem("BE-001", WorkerType.BE, "Normal task");
        PlanItem task1 = makeItem("BE-002", WorkerType.BE, "Critical path task");

        // Only task1 on critical path
        when(criticalPathCalculator.computeSchedule(any(Plan.class)))
                .thenReturn(new TropicalScheduler.ScheduleResult(
                        Map.of(), Map.of(), Map.of(),
                        List.of("BE-002"), 300_000.0));

        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(DUMMY_EMBEDDING);

        // task0: be-java=0.82, be-go=0.40  (prefers java)
        // task1: be-java=0.80, be-go=0.35  (also prefers java, but less strongly)
        // Without CP boost: task0→java(0.82), task1→go(0.35) total=1.17
        //                   task0→go(0.40), task1→java(0.80) total=1.20 ← global optimal
        // With CP boost (0.2) on task1: java becomes 0.80*1.2=0.96, go becomes 0.35*1.2=0.42
        //   task0→go(0.40), task1→java(boosted 0.96) total cost = -0.40 + -0.96 = -1.36
        //   task0→java(0.82), task1→go(boosted 0.42) total cost = -0.82 + -0.42 = -1.24
        //   → CP task gets java
        when(taskOutcomeService.predict(any(), eq("BE"), eq("be-java")))
                .thenReturn(new GpPrediction(0.82, 0.01))
                .thenReturn(new GpPrediction(0.80, 0.01));
        when(taskOutcomeService.predict(any(), eq("BE"), eq("be-go")))
                .thenReturn(new GpPrediction(0.40, 0.03))
                .thenReturn(new GpPrediction(0.35, 0.03));

        AssignmentResult result = solver.solve(List.of(task0, task1), plan);

        // CP task (BE-002) should get be-java due to boost
        assertThat(result.assignments().get("BE-002")).isEqualTo("be-java");
        assertThat(result.assignments().get("BE-001")).isEqualTo("be-go");
    }

    @Test
    void gpColdStart_uniformMu_anyAssignmentValid() {
        // GP cold start: all predictions return same mu (prior)
        PlanItem task0 = makeItem("BE-001", WorkerType.BE, "Task A");
        PlanItem task1 = makeItem("BE-002", WorkerType.BE, "Task B");

        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));

        when(taskOutcomeService.embedTask(anyString(), anyString())).thenReturn(DUMMY_EMBEDDING);

        // Uniform predictions (cold start prior)
        when(taskOutcomeService.predict(any(), eq("BE"), anyString()))
                .thenReturn(new GpPrediction(0.5, 1.0));

        AssignmentResult result = solver.solve(List.of(task0, task1), plan);

        // Both assigned, to different profiles
        assertThat(result.assignments()).hasSize(2);
        assertThat(result.assignments().get("BE-001"))
                .isNotEqualTo(result.assignments().get("BE-002"));
    }

    @Test
    void noProfiles_returnsEmptyAssignment() {
        PlanItem task0 = makeItem("AI-001", WorkerType.BE, "No profiles task");

        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of());

        AssignmentResult result = solver.solve(List.of(task0), plan);

        assertThat(result.assignments()).isEmpty();
        assertThat(result.totalCost()).isEqualTo(0.0);
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private PlanItem makeItem(String taskKey, WorkerType type, String title) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, title,
                "Description for " + taskKey, type, null, List.of(), List.of());
    }
}
