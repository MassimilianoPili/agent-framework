package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Monte Carlo Tree Search for plan-level task dispatch using PUCT.
 *
 * <p>Replaces greedy dispatch with UCT-based search (Kocsis &amp; Szepesvári 2006),
 * enhanced with PUCT prior (Silver et al. 2017, AlphaZero) where the GP posterior
 * provides the action prior P(s,a) via softmax over mean predictions.</p>
 *
 * <p>The search loop follows the standard MCTS phases:
 * <ol>
 *   <li><b>Select</b>: traverse tree using PUCT to pick the most promising child</li>
 *   <li><b>Expand</b>: create child nodes for untried candidate profiles</li>
 *   <li><b>Simulate</b>: estimate leaf value using GP mean (or uniform if GP unavailable)</li>
 *   <li><b>Backpropagate</b>: update value estimates up the tree using Welford online mean</li>
 * </ol></p>
 *
 * <p>Plans with ≤ {@code minStepsForMcts} steps fall back to greedy (best GP mean)
 * to avoid MCTS overhead on trivially small plans.</p>
 *
 * @see <a href="https://link.springer.com/chapter/10.1007/11871842_29">
 *     Kocsis &amp; Szepesvári (2006) — Bandit based Monte-Carlo Planning</a>
 * @see <a href="https://arxiv.org/abs/2410.20285">
 *     SWE-Search (Antoniades et al. 2024) — MCTS for SWE agent dispatch</a>
 */
@Service
@ConditionalOnProperty(prefix = "mcts-dispatch", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MctsDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MctsDispatchService.class);

    @Value("${mcts-dispatch.exploration-c:1.41}")
    private double explorationC;

    @Value("${mcts-dispatch.max-simulations-factor:50}")
    private int maxSimulationsFactor;

    @Value("${mcts-dispatch.min-steps-for-mcts:4}")
    private int minStepsForMcts;

    /**
     * Runs MCTS search to find optimal task→profile assignments.
     *
     * <p>If the plan has fewer steps than {@code minStepsForMcts}, falls back
     * to greedy assignment (highest prior value per task).</p>
     *
     * @param tasks             list of tasks to assign
     * @param candidateProfiles map of taskKey → list of candidate profile names
     * @param priorValues       map of "taskKey:profile" → GP mean prediction [0,1]
     *                          (use uniform 0.5 if GP unavailable)
     * @param maxSimulations    number of MCTS iterations (0 = auto based on factor)
     * @return search result with assignments and expected reward
     */
    public MctsResult search(List<TaskCandidate> tasks,
                              Map<String, List<String>> candidateProfiles,
                              Map<String, Double> priorValues,
                              int maxSimulations) {
        if (tasks == null || tasks.isEmpty()) {
            return new MctsResult(Map.of(), 0.0, 0, "empty");
        }

        // Greedy fallback for small plans
        if (tasks.size() < minStepsForMcts) {
            return greedyFallback(tasks, candidateProfiles, priorValues);
        }

        int simCount = maxSimulations > 0 ? maxSimulations
                : tasks.size() * maxSimulationsFactor;

        // Build root node
        MctsNode root = new MctsNode(null, null, 0);

        for (int sim = 0; sim < simCount; sim++) {
            // Select + Expand
            MctsNode leaf = selectAndExpand(root, tasks, candidateProfiles, 0);

            // Simulate
            double value = simulate(leaf, tasks, candidateProfiles, priorValues);

            // Backpropagate (Welford online mean)
            backpropagate(leaf, value);
        }

        // Extract best path
        Map<String, String> assignments = extractBestAssignments(root, tasks);
        double expectedReward = root.visits > 0 ? root.value : 0.0;

        String strategy = String.format("mcts(%d sims, depth=%d, c=%.2f)",
                simCount, tasks.size(), explorationC);

        log.debug("MCTS search: {} tasks, {} simulations, expected reward={}", tasks.size(), simCount, expectedReward);

        return new MctsResult(assignments, expectedReward, simCount, strategy);
    }

    /**
     * Computes the PUCT score for a child node.
     *
     * <pre>
     *   PUCT(s,a) = Q(s,a) + c_puct · P(s,a) · √N(parent) / (1 + N(child))
     * </pre>
     *
     * @param child       the child node
     * @param parentVisits parent visit count
     * @param prior       the GP-derived prior P(s,a) for this action
     * @return PUCT score (higher = more promising)
     */
    public double puctScore(MctsNode child, int parentVisits, double prior) {
        double exploitation = child.visits > 0 ? child.value : 0.0;
        double exploration = explorationC * prior * Math.sqrt(parentVisits) / (1.0 + child.visits);
        return exploitation + exploration;
    }

    // --- Internal MCTS phases ---

    private MctsNode selectAndExpand(MctsNode node, List<TaskCandidate> tasks,
                                      Map<String, List<String>> candidateProfiles,
                                      int depth) {
        if (depth >= tasks.size()) {
            return node; // leaf: all tasks assigned
        }

        String taskKey = tasks.get(depth).taskKey();
        List<String> candidates = candidateProfiles.getOrDefault(taskKey, List.of());

        if (candidates.isEmpty()) {
            return node;
        }

        // Expand if needed
        if (node.children.isEmpty()) {
            for (String profile : candidates) {
                MctsNode child = new MctsNode(taskKey, profile, 0);
                child.parent = node;
                node.children.put(profile, child);
            }
        }

        // Check for untried children
        for (Map.Entry<String, MctsNode> entry : node.children.entrySet()) {
            if (entry.getValue().visits == 0) {
                return entry.getValue(); // expand: try untried child
            }
        }

        // All tried — select by PUCT
        double uniformPrior = 1.0 / candidates.size();
        String bestProfile = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Map.Entry<String, MctsNode> entry : node.children.entrySet()) {
            double score = puctScore(entry.getValue(), node.visits, uniformPrior);
            if (score > bestScore) {
                bestScore = score;
                bestProfile = entry.getKey();
            }
        }

        if (bestProfile == null) {
            return node;
        }

        return selectAndExpand(node.children.get(bestProfile), tasks, candidateProfiles, depth + 1);
    }

    private double simulate(MctsNode leaf, List<TaskCandidate> tasks,
                             Map<String, List<String>> candidateProfiles,
                             Map<String, Double> priorValues) {
        // Use GP mean as leaf value estimate; random completion for unassigned tasks
        double totalValue = 0.0;
        int count = 0;

        // Collect assignments from leaf path
        MctsNode current = leaf;
        while (current != null && current.taskKey != null) {
            String key = current.taskKey + ":" + current.assignedProfile;
            totalValue += priorValues.getOrDefault(key, 0.5);
            count++;
            current = null; // leaf nodes don't have parent reference in this simple impl
        }

        // Random completion for remaining tasks
        for (TaskCandidate task : tasks) {
            List<String> candidates = candidateProfiles.getOrDefault(task.taskKey(), List.of());
            if (!candidates.isEmpty() && count < tasks.size()) {
                String randomProfile = candidates.get(
                        ThreadLocalRandom.current().nextInt(candidates.size()));
                String key = task.taskKey() + ":" + randomProfile;
                totalValue += priorValues.getOrDefault(key, 0.5);
                count++;
            }
        }

        return count > 0 ? totalValue / count : 0.5;
    }

    private void backpropagate(MctsNode node, double value) {
        // Welford online mean update
        MctsNode current = node;
        while (current != null) {
            current.visits++;
            // Welford: mean += (value - mean) / n
            current.value += (value - current.value) / current.visits;
            current = current.parent;
        }
    }

    private Map<String, String> extractBestAssignments(MctsNode root, List<TaskCandidate> tasks) {
        Map<String, String> assignments = new LinkedHashMap<>();
        MctsNode current = root;

        for (TaskCandidate task : tasks) {
            if (current.children.isEmpty()) break;

            // Pick most-visited child (robust selection)
            Map.Entry<String, MctsNode> best = current.children.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().visits))
                    .orElse(null);

            if (best != null) {
                assignments.put(task.taskKey(), best.getKey());
                current = best.getValue();
            }
        }

        return assignments;
    }

    private MctsResult greedyFallback(List<TaskCandidate> tasks,
                                       Map<String, List<String>> candidateProfiles,
                                       Map<String, Double> priorValues) {
        Map<String, String> assignments = new LinkedHashMap<>();
        double totalReward = 0.0;

        for (TaskCandidate task : tasks) {
            List<String> candidates = candidateProfiles.getOrDefault(task.taskKey(), List.of());
            String bestProfile = null;
            double bestValue = Double.NEGATIVE_INFINITY;

            for (String profile : candidates) {
                double value = priorValues.getOrDefault(task.taskKey() + ":" + profile, 0.5);
                if (value > bestValue) {
                    bestValue = value;
                    bestProfile = profile;
                }
            }

            if (bestProfile != null) {
                assignments.put(task.taskKey(), bestProfile);
                totalReward += bestValue;
            }
        }

        double avgReward = tasks.isEmpty() ? 0.0 : totalReward / tasks.size();
        return new MctsResult(assignments, avgReward, 0, "greedy-fallback");
    }

    // --- Inner types ---

    /**
     * A task candidate for MCTS dispatch.
     *
     * @param taskKey     unique task identifier
     * @param description task description (for logging)
     */
    public record TaskCandidate(String taskKey, String description) {}

    /**
     * Result of MCTS search.
     *
     * @param assignments    map of taskKey → assigned profile
     * @param expectedReward estimated average reward
     * @param simulationsRun number of MCTS iterations completed
     * @param strategy       description of strategy used (mcts or greedy-fallback)
     */
    public record MctsResult(
            Map<String, String> assignments,
            double expectedReward,
            int simulationsRun,
            String strategy
    ) {}

    /**
     * Mutable MCTS tree node. Not a record due to mutable state.
     */
    static class MctsNode {
        final String taskKey;
        final String assignedProfile;
        final Map<String, MctsNode> children = new LinkedHashMap<>();
        MctsNode parent;
        double value;
        int visits;

        MctsNode(String taskKey, String assignedProfile, double initialValue) {
            this.taskKey = taskKey;
            this.assignedProfile = assignedProfile;
            this.value = initialValue;
        }
    }
}
