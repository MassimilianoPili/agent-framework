package com.agentframework.orchestrator.analytics.mcts;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCTS tree node extended with EMA rewards and RAVE statistics.
 *
 * <p>This node extends the basic MCTS node from {@link com.agentframework.orchestrator.analytics.MctsDispatchService}
 * with two key additions for online learning:</p>
 *
 * <ul>
 *   <li><b>EMA reward</b>: Exponential Moving Average that gives more weight to recent
 *       observations, enabling adaptation to non-stationary reward distributions
 *       (e.g., worker performance changing over time)</li>
 *   <li><b>RAVE statistics</b> (Rapid Action Value Estimation, Gelly &amp; Silver 2007):
 *       All-Moves-As-First heuristic that shares statistics across the tree,
 *       providing warm-start for new nodes and faster convergence</li>
 * </ul>
 *
 * <h3>Combined value formula</h3>
 * <pre>
 *   V(s,a) = (1 - β) × Q_MC(s,a) + β × Q_RAVE(s,a)
 *   β(n) = raveK / (raveK + 3n)    where n = visit count
 * </pre>
 *
 * <p>As n → ∞, β → 0 and the node relies entirely on MC estimates.
 * At n = 0, β = 1 and RAVE provides initial guidance.</p>
 *
 * @see <a href="https://hal.inria.fr/inria-00164003/document">
 *     Gelly &amp; Silver (2007) — Combining Online and Offline Knowledge in UCT</a>
 */
public class MctsOnlineNode {

    /** The action (worker profile) this node represents. */
    final String profile;

    /** The task key this node is assigned to (null for root). */
    final String taskKey;

    /** Parent node (null for root). */
    MctsOnlineNode parent;

    /** Children keyed by profile name. */
    final Map<String, MctsOnlineNode> children = new LinkedHashMap<>();

    // ── MC statistics ────────────────────────────────────────────────────────

    /** Visit count. */
    int visits;

    /** Standard MC value (Welford online mean). */
    double mcValue;

    /** EMA-decayed reward value (adapts to distribution shift). */
    double emaReward;

    // ── RAVE statistics ──────────────────────────────────────────────────────

    /** RAVE value estimate (All-Moves-As-First heuristic). */
    double raveValue;

    /** RAVE visit count (number of simulations where this action appeared). */
    int raveVisits;

    public MctsOnlineNode(String taskKey, String profile) {
        this.taskKey = taskKey;
        this.profile = profile;
    }

    /**
     * Combined value blending MC estimates with RAVE warm-start.
     *
     * <p>Uses the schedule β(n) = raveK / (raveK + 3n) from Gelly &amp; Silver (2007).
     * When visits are low, RAVE dominates. As visits grow, MC takes over.</p>
     *
     * @param raveK the RAVE equivalence parameter (higher = more RAVE influence)
     * @return blended value estimate
     */
    public double combinedValue(int raveK) {
        if (visits == 0 && raveVisits == 0) {
            return 0.0;
        }

        double beta = (double) raveK / (raveK + 3.0 * visits);

        double mc = visits > 0 ? emaReward : 0.0;
        double rave = raveVisits > 0 ? raveValue : mc;

        return (1.0 - beta) * mc + beta * rave;
    }

    /**
     * PUCT score with RAVE-blended value.
     *
     * <pre>
     *   PUCT(s,a) = V_combined(s,a) + c × prior × √N(parent) / (1 + N(child))
     * </pre>
     *
     * @param parentVisits parent visit count
     * @param prior        GP-derived action prior P(s,a)
     * @param explorationC exploration constant
     * @param raveK        RAVE equivalence parameter
     * @return PUCT score (higher = more promising)
     */
    public double puctScoreWithRave(int parentVisits, double prior,
                                     double explorationC, int raveK) {
        double exploitation = combinedValue(raveK);
        double exploration = explorationC * prior * Math.sqrt(parentVisits) / (1.0 + visits);
        return exploitation + exploration;
    }

    /**
     * Updates MC value using Welford online mean, then applies EMA decay.
     *
     * @param reward  the backpropagated reward value
     * @param emaAlpha EMA decay factor (higher = more weight to recent reward)
     */
    void updateMc(double reward, double emaAlpha) {
        visits++;
        // Welford online mean
        mcValue += (reward - mcValue) / visits;
        // EMA: biased toward recent rewards
        if (visits == 1) {
            emaReward = reward;
        } else {
            emaReward = emaAlpha * reward + (1.0 - emaAlpha) * emaReward;
        }
    }

    /**
     * Updates RAVE statistics for this action.
     *
     * @param reward the reward from a simulation where this action appeared
     */
    void updateRave(double reward) {
        raveVisits++;
        raveValue += (reward - raveValue) / raveVisits;
    }

    /**
     * Applies EMA decay sweep: slightly fades the EMA reward toward the MC mean.
     * Called periodically to prevent stale EMA values from dominating.
     *
     * @param decayFactor factor in (0,1); 0.95 = 5% decay toward MC mean per sweep
     */
    void decayEma(double decayFactor) {
        if (visits > 0) {
            emaReward = decayFactor * emaReward + (1.0 - decayFactor) * mcValue;
        }
        for (MctsOnlineNode child : children.values()) {
            child.decayEma(decayFactor);
        }
    }
}
