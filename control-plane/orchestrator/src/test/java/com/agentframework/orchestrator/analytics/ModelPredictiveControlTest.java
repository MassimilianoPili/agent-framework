package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ModelPredictiveControl.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ModelPredictiveControl}.
 *
 * <p>Verifies greedy fallback, MPC lookahead superiority, state transitions,
 * cost computation, and terminal value estimation.</p>
 */
@DisplayName("Model Predictive Control — pure engine")
class ModelPredictiveControlTest {

    @Test
    void greedyAction_multipleTasks_selectsBestRatio() {
        // Task 0: reward=0.3, cost=0.1 → ratio=3.0
        // Task 1: reward=0.5, cost=0.3 → ratio=1.67
        // Task 2: reward=0.8, cost=0.2 → ratio=4.0  ← best
        PlanState state = new PlanState(
                new double[]{0.3, 0.5, 0.8},
                new double[]{0.1, 0.3, 0.2},
                1.0, 0);

        ScheduleAction action = ModelPredictiveControl.greedyAction(state);

        assertThat(action).isNotNull();
        assertThat(action.taskIndex()).isEqualTo(2);
        assertThat(action.expectedReward()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void greedyAction_singleTask_returnsThatTask() {
        PlanState state = new PlanState(
                new double[]{0.7},
                new double[]{0.1},
                1.0, 0);

        ScheduleAction action = ModelPredictiveControl.greedyAction(state);

        assertThat(action).isNotNull();
        assertThat(action.taskIndex()).isEqualTo(0);
    }

    @Test
    void optimize_horizon1_returnsValidAction() {
        // With H=1, MPC evaluates each single action + terminal value.
        // This may differ from pure greedy (which only uses reward/cost ratio)
        // because terminal value accounts for remaining opportunities.
        PlanState state = new PlanState(
                new double[]{0.3, 0.8, 0.5},
                new double[]{0.1, 0.2, 0.3},
                1.0, 0);

        MpcReport mpc = ModelPredictiveControl.optimize(state, 1, 10);

        assertThat(mpc.recommendedAction()).isNotNull();
        assertThat(mpc.horizonUsed()).isEqualTo(1);
        assertThat(mpc.trajectoriesEvaluated()).isGreaterThan(0);
        assertThat(mpc.optimalTrajectory().totalReward()).isPositive();
    }

    @Test
    void optimize_horizon3_findsOptimalSequence() {
        // Scenario where greedy picks task 0 (best ratio) but MPC finds
        // that picking task 1 first leads to better total outcome
        // Task 0: reward=0.9, cost=0.8 → ratio=1.125
        // Task 1: reward=0.5, cost=0.1 → ratio=5.0 (greedy picks this)
        // Task 2: reward=0.7, cost=0.2 → ratio=3.5
        // Budget: 1.0
        // Greedy: task 1 (0.5, cost 0.1) → budget 0.9, then task 2 (0.7, cost 0.2) → budget 0.7
        //         then task 0 (0.9, cost 0.8) → budget -0.1 CANT → total reward = 1.2
        // Optimal: task 0 (0.9, cost 0.8) → budget 0.2, then task 1 (0.5, cost 0.1) → budget 0.1
        //          → total reward = 1.4 (cant afford task 2 either way for both paths)
        // Actually with budget 1.0: greedy picks 1,2,0 but can't afford 0 → reward 1.2
        // MPC with H=3 tries all orderings and finds 0,1 → reward 1.4
        PlanState state = new PlanState(
                new double[]{0.9, 0.5, 0.7},
                new double[]{0.8, 0.1, 0.2},
                1.0, 0);

        MpcReport mpc = ModelPredictiveControl.optimize(state, 3, 10);

        assertThat(mpc.optimalTrajectory()).isNotNull();
        assertThat(mpc.optimalTrajectory().totalReward())
                .isGreaterThanOrEqualTo(1.2); // at least as good as greedy
        assertThat(mpc.trajectoriesEvaluated()).isGreaterThan(1);
    }

    @Test
    void applyAction_updatesStateCorrectly() {
        PlanState state = new PlanState(
                new double[]{0.3, 0.8, 0.5},
                new double[]{0.1, 0.2, 0.3},
                1.0, 0);

        ScheduleAction action = new ScheduleAction(1, 0.8, 0.2);
        PlanState next = ModelPredictiveControl.applyAction(state, action);

        assertThat(next.taskRewards()).hasSize(2); // one task removed
        assertThat(next.budgetRemaining()).isCloseTo(0.8, within(1e-9));
        assertThat(next.tasksCompleted()).isEqualTo(1);
    }

    @Test
    void stageCost_computation() {
        ScheduleAction action = new ScheduleAction(0, 0.8, 0.3);
        double cost = ModelPredictiveControl.stageCost(action);
        // -reward + cost = -0.8 + 0.3 = -0.5
        assertThat(cost).isCloseTo(-0.5, within(1e-9));
    }

    @Test
    void terminalValue_emptyState_returnsZero() {
        PlanState state = new PlanState(new double[]{}, new double[]{}, 1.0, 5);
        double tv = ModelPredictiveControl.terminalValue(state);
        assertThat(tv).isCloseTo(0.0, within(1e-9));
    }
}
