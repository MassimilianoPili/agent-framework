package com.agentframework.orchestrator.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Model Predictive Control (MPC) for task scheduling optimization.
 *
 * <p>MPC optimizes dispatch decisions over a finite prediction horizon H,
 * then executes only the first action (receding horizon principle).
 * This captures inter-task dependencies that greedy single-step dispatch misses.</p>
 *
 * <p>Formulation: minimize Σ_{k=0}^{H-1} L(x_k, u_k) + V_f(x_H), where
 * L is the stage cost (-reward + token cost), V_f is the terminal value
 * (estimated remaining value), and the dynamics model is deterministic
 * task consumption.</p>
 *
 * @see <a href="https://doi.org/10.1007/978-3-319-24853-0">
 *     Camacho &amp; Bordons (2007), Model Predictive Control, Springer</a>
 */
public final class ModelPredictiveControl {

    private ModelPredictiveControl() {}

    /**
     * Plan state: snapshot of ready tasks, their expected rewards/costs, and budget.
     *
     * @param taskRewards     expected reward for each ready task (GP mu)
     * @param taskCosts       token cost for each task
     * @param budgetRemaining remaining token budget
     * @param tasksCompleted  count of tasks already completed
     */
    public record PlanState(
            double[] taskRewards,
            double[] taskCosts,
            double budgetRemaining,
            int tasksCompleted
    ) {}

    /**
     * Schedule action: dispatch task at index i.
     *
     * @param taskIndex      index into the PlanState task arrays
     * @param expectedReward GP-predicted reward for this assignment
     * @param cost           token cost of this dispatch
     */
    public record ScheduleAction(int taskIndex, double expectedReward, double cost) {}

    /**
     * Trajectory: ordered sequence of actions with aggregate metrics.
     *
     * @param actions        sequence of dispatch decisions
     * @param totalReward    sum of expected rewards across the trajectory
     * @param totalCost      sum of token costs across the trajectory
     * @param objectiveValue totalReward - totalCost (maximized by MPC)
     */
    public record MpcTrajectory(
            List<ScheduleAction> actions,
            double totalReward,
            double totalCost,
            double objectiveValue
    ) {}

    /**
     * MPC optimization report.
     *
     * @param recommendedAction     first action of the optimal trajectory (execute this)
     * @param optimalTrajectory     the full lookahead trajectory
     * @param horizonUsed           effective horizon (min of H and available tasks)
     * @param trajectoriesEvaluated number of trajectories explored
     */
    public record MpcReport(
            ScheduleAction recommendedAction,
            MpcTrajectory optimalTrajectory,
            int horizonUsed,
            int trajectoriesEvaluated
    ) {}

    /**
     * Greedy single-step dispatch: select the task with the best reward/cost ratio.
     *
     * <p>This is the fallback when H=1 or when MPC is too expensive.</p>
     *
     * @param state current plan state
     * @return best single-step action, or null if no tasks available
     */
    static ScheduleAction greedyAction(PlanState state) {
        if (state.taskRewards.length == 0) return null;

        int bestIdx = -1;
        double bestRatio = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < state.taskRewards.length; i++) {
            if (state.taskCosts[i] > state.budgetRemaining) continue;
            double ratio = state.taskCosts[i] > 0
                    ? state.taskRewards[i] / state.taskCosts[i]
                    : state.taskRewards[i];
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return null;
        return new ScheduleAction(bestIdx, state.taskRewards[bestIdx], state.taskCosts[bestIdx]);
    }

    /**
     * Stage cost for a single action: negated reward plus token cost.
     *
     * <p>MPC minimizes total stage cost, so high reward = low cost.</p>
     *
     * @param action the dispatch action
     * @return -reward + cost
     */
    static double stageCost(ScheduleAction action) {
        return -action.expectedReward + action.cost;
    }

    /**
     * Terminal value estimate: average remaining reward scaled by completion ratio.
     *
     * <p>Provides a heuristic for the value beyond the prediction horizon.</p>
     *
     * @param state current plan state
     * @return estimated future value (non-negative)
     */
    static double terminalValue(PlanState state) {
        if (state.taskRewards.length == 0) return 0.0;

        double sumRewards = 0.0;
        int affordable = 0;
        for (int i = 0; i < state.taskRewards.length; i++) {
            if (state.taskCosts[i] <= state.budgetRemaining) {
                sumRewards += state.taskRewards[i];
                affordable++;
            }
        }
        return affordable > 0 ? sumRewards / affordable * Math.min(affordable, 3) : 0.0;
    }

    /**
     * Apply an action to the current state, producing the successor state.
     *
     * <p>The selected task is removed from the ready set, budget is decremented,
     * and tasksCompleted is incremented.</p>
     *
     * @param state  current state
     * @param action action to apply
     * @return new state after the action
     */
    static PlanState applyAction(PlanState state, ScheduleAction action) {
        int n = state.taskRewards.length;
        int idx = action.taskIndex;

        double[] newRewards = new double[n - 1];
        double[] newCosts = new double[n - 1];
        int dest = 0;
        for (int i = 0; i < n; i++) {
            if (i != idx) {
                newRewards[dest] = state.taskRewards[i];
                newCosts[dest] = state.taskCosts[i];
                dest++;
            }
        }

        return new PlanState(
                newRewards, newCosts,
                state.budgetRemaining - action.cost,
                state.tasksCompleted + 1
        );
    }

    /**
     * MPC optimization via depth-limited enumeration with pruning.
     *
     * <p>Explores up to {@code maxCandidates} best tasks at each level,
     * up to depth {@code horizon}. Returns the trajectory with the highest
     * objective value (total reward - total cost + terminal value).</p>
     *
     * <p>Complexity: O(min(n, maxCandidates)^horizon).</p>
     *
     * @param state         current plan state
     * @param horizon       prediction horizon (number of lookahead steps)
     * @param maxCandidates maximum branches per level (pruning factor)
     * @return MPC report with recommended first action and full trajectory
     */
    static MpcReport optimize(PlanState state, int horizon, int maxCandidates) {
        if (state.taskRewards.length == 0) {
            return new MpcReport(null,
                    new MpcTrajectory(List.of(), 0, 0, 0), 0, 0);
        }

        int effectiveHorizon = Math.min(horizon, state.taskRewards.length);
        int[] counter = {0}; // mutable counter for trajectory count

        MpcTrajectory best = search(state, effectiveHorizon, maxCandidates,
                new ArrayList<>(), 0, 0, counter);

        ScheduleAction recommended = best.actions().isEmpty() ? null : best.actions().get(0);
        return new MpcReport(recommended, best, effectiveHorizon, counter[0]);
    }

    /**
     * Recursive depth-limited search with candidate pruning.
     */
    private static MpcTrajectory search(PlanState state, int remainingDepth,
                                          int maxCandidates, List<ScheduleAction> path,
                                          double accReward, double accCost, int[] counter) {
        // Base case: horizon exhausted or no tasks left
        if (remainingDepth == 0 || state.taskRewards.length == 0) {
            counter[0]++;
            double tv = terminalValue(state);
            return new MpcTrajectory(
                    new ArrayList<>(path), accReward, accCost,
                    accReward - accCost + tv
            );
        }

        // Collect affordable candidates sorted by reward/cost ratio (top-K)
        int n = state.taskRewards.length;
        int[][] candidates = new int[n][1];
        double[] ratios = new double[n];
        int numCandidates = 0;

        for (int i = 0; i < n; i++) {
            if (state.taskCosts[i] <= state.budgetRemaining) {
                candidates[numCandidates][0] = i;
                ratios[numCandidates] = state.taskCosts[i] > 0
                        ? state.taskRewards[i] / state.taskCosts[i]
                        : state.taskRewards[i];
                numCandidates++;
            }
        }

        if (numCandidates == 0) {
            counter[0]++;
            return new MpcTrajectory(
                    new ArrayList<>(path), accReward, accCost,
                    accReward - accCost + terminalValue(state)
            );
        }

        // Sort candidates by ratio descending, take top maxCandidates
        Integer[] indices = new Integer[numCandidates];
        for (int i = 0; i < numCandidates; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(ratios[b], ratios[a]));

        int limit = Math.min(numCandidates, maxCandidates);
        MpcTrajectory bestTrajectory = null;

        for (int c = 0; c < limit; c++) {
            int taskIdx = candidates[indices[c]][0];
            ScheduleAction action = new ScheduleAction(
                    taskIdx, state.taskRewards[taskIdx], state.taskCosts[taskIdx]);

            path.add(action);
            PlanState next = applyAction(state, action);

            // Reindex: task indices shift after removal
            MpcTrajectory traj = search(next, remainingDepth - 1, maxCandidates,
                    path, accReward + action.expectedReward(),
                    accCost + action.cost(), counter);

            if (bestTrajectory == null || traj.objectiveValue() > bestTrajectory.objectiveValue()) {
                bestTrajectory = traj;
            }

            path.remove(path.size() - 1);
        }

        return bestTrajectory;
    }
}
