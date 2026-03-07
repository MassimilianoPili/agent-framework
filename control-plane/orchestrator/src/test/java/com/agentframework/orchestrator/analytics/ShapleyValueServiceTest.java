package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ShapleyValue.ShapleyReport;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShapleyValueService}.
 *
 * <p>Verifies plan-level Shapley attribution with multiple workers, empty data,
 * single worker, and Monte Carlo fallback for large teams.</p>
 */
@ExtendWith(MockitoExtension.class)
class ShapleyValueServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ShapleyValueService service;

    @BeforeEach
    void setUp() {
        service = new ShapleyValueService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "monteCarloSamples", 10000);
        ReflectionTestUtils.setField(service, "maxExactPlayers", 10);
    }

    /**
     * Creates a mock row matching findOutcomesByPlanId format.
     * [0]=worker_profile(String), [1]=actual_reward(Number),
     * [2]=worker_type(String), [3]=task_key(String)
     */
    private Object[] makeOutcome(String profile, double reward, String taskKey) {
        return new Object[]{profile, reward, "BE", taskKey};
    }

    @Test
    @DisplayName("computeForPlan with multiple workers returns Shapley attribution report")
    void computeForPlan_withMultipleWorkers_returnsAttribution() {
        UUID planId = UUID.randomUUID();

        List<Object[]> outcomes = new ArrayList<>();
        // be-java did 3 tasks with rewards 0.8, 0.7, 0.9 → sum = 2.4
        outcomes.add(makeOutcome("be-java", 0.8, "task-1"));
        outcomes.add(makeOutcome("be-java", 0.7, "task-2"));
        outcomes.add(makeOutcome("be-java", 0.9, "task-3"));
        // be-go did 2 tasks with rewards 0.6, 0.5 → sum = 1.1
        outcomes.add(makeOutcome("be-go", 0.6, "task-4"));
        outcomes.add(makeOutcome("be-go", 0.5, "task-5"));
        // fe-react did 1 task with reward 0.7 → sum = 0.7
        outcomes.add(makeOutcome("fe-react", 0.7, "task-6"));

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomes);

        ShapleyReport report = service.computeForPlan(planId);

        assertThat(report).isNotNull();
        assertThat(report.playerNames()).hasSize(3);
        assertThat(report.shapleyValues()).hasSize(3);
        assertThat(report.exact()).isTrue();
        assertThat(report.efficiencyCheck()).isTrue();

        // Grand coalition = 2.4 + 1.1 + 0.7 = 4.2
        assertThat(report.grandCoalitionValue()).isCloseTo(4.2, within(1e-9));

        // Additive game → Shapley = individual contribution
        double sumShapley = Arrays.stream(report.shapleyValues()).sum();
        assertThat(sumShapley).isCloseTo(4.2, within(1e-6));
    }

    @Test
    @DisplayName("computeForPlan with no plan outcomes returns null")
    void computeForPlan_noOutcomes_returnsNull() {
        UUID planId = UUID.randomUUID();
        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(new ArrayList<>());

        ShapleyReport report = service.computeForPlan(planId);

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("computeForPlan with single worker assigns full credit")
    void computeForPlan_singleWorker_getsFullCredit() {
        UUID planId = UUID.randomUUID();

        List<Object[]> outcomes = new ArrayList<>();
        outcomes.add(makeOutcome("be-java", 0.8, "task-1"));
        outcomes.add(makeOutcome("be-java", 0.7, "task-2"));

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomes);

        ShapleyReport report = service.computeForPlan(planId);

        assertThat(report).isNotNull();
        assertThat(report.playerNames()).hasSize(1);
        assertThat(report.shapleyValues()[0]).isCloseTo(1.5, within(1e-9));
        assertThat(report.grandCoalitionValue()).isCloseTo(1.5, within(1e-9));
    }

    @Test
    @DisplayName("computeForPlan with many workers uses Monte Carlo approximation")
    void computeForPlan_manyWorkers_usesMonteCarloApproximation() {
        // Set maxExactPlayers to 2 so that 3 players triggers Monte Carlo
        ReflectionTestUtils.setField(service, "maxExactPlayers", 2);

        UUID planId = UUID.randomUUID();

        List<Object[]> outcomes = new ArrayList<>();
        outcomes.add(makeOutcome("be-java", 0.8, "task-1"));
        outcomes.add(makeOutcome("be-go", 0.6, "task-2"));
        outcomes.add(makeOutcome("fe-react", 0.7, "task-3"));

        when(taskOutcomeRepository.findOutcomesByPlanId(planId)).thenReturn(outcomes);

        ShapleyReport report = service.computeForPlan(planId);

        assertThat(report).isNotNull();
        assertThat(report.exact()).isFalse();
        // Monte Carlo should still approximate the additive Shapley values
        assertThat(report.shapleyValues()[0]).isCloseTo(0.8, within(0.05));
        assertThat(report.shapleyValues()[1]).isCloseTo(0.6, within(0.05));
        assertThat(report.shapleyValues()[2]).isCloseTo(0.7, within(0.05));
    }
}
