package com.agentframework.orchestrator.analytics.mcts;

import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.model.GpPosterior;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.analytics.MctsDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Online MCTS policy service with EMA decay, RAVE warm-start, and BAMCP root sampling.
 *
 * <p>Extends the base {@link MctsDispatchService} with three key capabilities
 * for online learning in non-stationary environments:</p>
 *
 * <ol>
 *   <li><b>EMA decay</b>: Recent rewards are weighted more heavily than old ones,
 *       allowing the tree to adapt to changing worker performance without
 *       discarding the entire history</li>
 *   <li><b>RAVE warm-start</b>: New nodes start with RAVE estimates from other
 *       simulations, dramatically reducing the exploration period for rare
 *       task→profile combinations</li>
 *   <li><b>BAMCP root sampling</b>: Each simulation samples a root belief from
 *       the GP posterior, ensuring the search explores the full uncertainty
 *       space rather than converging prematurely on the MAP estimate</li>
 * </ol>
 *
 * <p>The tree is <b>persistent</b> across invocations — unlike the base MCTS which
 * rebuilds from scratch, this service maintains the tree state and incrementally
 * updates it with new observations. Periodic EMA decay sweeps prevent stale
 * estimates from dominating.</p>
 *
 * @see MctsOnlineNode
 * @see BamcpRootSampler
 * @see MctsOnlineConfig
 * @see <a href="https://arxiv.org/abs/2410.20285">
 *     SWE-Search (Antoniades et al. 2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.mcts-online", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(MctsOnlineConfig.class)
public class MctsOnlinePolicyService {

    private static final Logger log = LoggerFactory.getLogger(MctsOnlinePolicyService.class);

    private final GaussianProcessEngine gpEngine;
    private final BamcpRootSampler bamcpSampler;
    private final MctsOnlineConfig config;

    /** Persistent MCTS trees per "plan context" (keyed by a context identifier). */
    private final Map<String, MctsOnlineNode> persistentTrees = new ConcurrentHashMap<>();

    /** RAVE statistics: global action values shared across the tree. */
    private final Map<String, RaveEntry> globalRave = new ConcurrentHashMap<>();

    public MctsOnlinePolicyService(GaussianProcessEngine gpEngine,
                                    MctsOnlineConfig config) {
        this.gpEngine = gpEngine;
        this.bamcpSampler = new BamcpRootSampler(gpEngine);
        this.config = config;
    }

    /**
     * Selects the best worker profile for a task using online MCTS.
     *
     * @param contextKey        persistent tree identifier (e.g., plan ID or "global")
     * @param taskKey           the task to assign
     * @param candidateProfiles available worker profiles for this task
     * @param gpPosterior       fitted GP posterior (null for cold start)
     * @param taskEmbedding     task embedding for GP prediction (null for cold start)
     * @param maxSimulations    number of MCTS iterations
     * @return selection result with chosen profile, confidence, and diagnostics
     */
    public SelectionResult selectProfile(String contextKey, String taskKey,
                                          List<String> candidateProfiles,
                                          GpPosterior gpPosterior,
                                          float[] taskEmbedding,
                                          int maxSimulations) {
        if (candidateProfiles == null || candidateProfiles.isEmpty()) {
            return new SelectionResult(null, 0.0, 0, "no-candidates");
        }

        if (candidateProfiles.size() == 1) {
            return new SelectionResult(candidateProfiles.getFirst(), 1.0, 0, "single-candidate");
        }

        // Get or create persistent tree root
        MctsOnlineNode root = persistentTrees.computeIfAbsent(
                contextKey, k -> new MctsOnlineNode(null, null));

        // Compute GP-derived priors for candidates
        double[] priors = computePriors(candidateProfiles, gpPosterior, taskEmbedding);

        // BAMCP: sample root beliefs
        double[] rootSamples = bamcpSampler.sampleRoots(
                gpPosterior, taskEmbedding, config.bamcpSamples());

        // Run MCTS iterations with BAMCP sampling
        int sims = maxSimulations > 0 ? maxSimulations : 50;
        for (int sim = 0; sim < sims; sim++) {
            // BAMCP: use a sampled root value for this iteration
            double rootBelief = rootSamples[sim % rootSamples.length];

            // Select or expand
            MctsOnlineNode leaf = selectAndExpand(root, taskKey, candidateProfiles, priors);

            // Simulate: blend rootBelief with prior value
            double value = simulate(leaf, priors, candidateProfiles, rootBelief);

            // Backpropagate with EMA
            backpropagateWithEma(leaf, value);

            // Update global RAVE for the selected action
            if (leaf.profile != null) {
                updateGlobalRave(leaf.profile, value);
            }
        }

        // Extract best profile (most visited child)
        String bestProfile = extractBestProfile(root, taskKey);
        double confidence = computeConfidence(root, taskKey, bestProfile);

        String strategy = String.format("mcts-online(sims=%d, ema=%.2f, raveK=%d, bamcp=%d)",
                sims, config.emaAlpha(), config.raveK(), config.bamcpSamples());

        log.debug("MCTS Online: task={} selected={} confidence={} sims={}",
                taskKey, bestProfile, String.format("%.4f", confidence), sims);

        return new SelectionResult(bestProfile, confidence, sims, strategy);
    }

    /**
     * Records an observed reward for a completed task, updating the persistent tree.
     *
     * @param contextKey the tree context
     * @param taskKey    the completed task
     * @param profile    the profile that executed the task
     * @param reward     the observed reward [0, 1]
     */
    public void recordOutcome(String contextKey, String taskKey, String profile, double reward) {
        MctsOnlineNode root = persistentTrees.get(contextKey);
        if (root == null) return;

        // Find and update the relevant node in the tree
        MctsOnlineNode child = findChild(root, taskKey, profile);
        if (child != null) {
            child.updateMc(reward, config.emaAlpha());
        }

        // Update global RAVE
        updateGlobalRave(profile, reward);

        log.debug("MCTS Online outcome: task={} profile={} reward={}", taskKey, profile, reward);
    }

    /**
     * Periodic EMA decay sweep on all persistent trees.
     * Prevents stale EMA values from dominating when no new observations arrive.
     */
    @Scheduled(fixedDelayString = "${agent-framework.mcts-online.decay-interval-ms:60000}")
    void emaDecaySweep() {
        double decayFactor = 1.0 - config.emaAlpha() * 0.1; // gentle decay per sweep
        for (MctsOnlineNode root : persistentTrees.values()) {
            root.decayEma(decayFactor);
        }
        log.debug("EMA decay sweep completed: {} trees", persistentTrees.size());
    }

    // ── Internal MCTS phases ─────────────────────────────────────────────────

    private MctsOnlineNode selectAndExpand(MctsOnlineNode node, String taskKey,
                                            List<String> candidates, double[] priors) {
        // Expand children if needed
        if (node.children.isEmpty()) {
            for (int i = 0; i < candidates.size(); i++) {
                String profile = candidates.get(i);
                MctsOnlineNode child = new MctsOnlineNode(taskKey, profile);
                child.parent = node;

                // Warm-start from global RAVE
                RaveEntry globalEntry = globalRave.get(profile);
                if (globalEntry != null) {
                    child.raveValue = globalEntry.value;
                    child.raveVisits = globalEntry.visits;
                }

                node.children.put(profile, child);
            }
        }

        // Check for untried children
        for (MctsOnlineNode child : node.children.values()) {
            if (child.visits == 0) {
                return child;
            }
        }

        // All tried — select by PUCT with RAVE
        MctsOnlineNode bestChild = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        int idx = 0;
        for (MctsOnlineNode child : node.children.values()) {
            double prior = idx < priors.length ? priors[idx] : 1.0 / candidates.size();
            double score = child.puctScoreWithRave(
                    node.visits, prior, config.explorationC(), config.raveK());
            if (score > bestScore) {
                bestScore = score;
                bestChild = child;
            }
            idx++;
        }

        return bestChild != null ? bestChild : node;
    }

    private double simulate(MctsOnlineNode leaf, double[] priors,
                             List<String> candidates, double rootBelief) {
        // Blend GP prior with BAMCP sampled root belief
        if (leaf.profile != null) {
            int idx = candidates.indexOf(leaf.profile);
            double prior = idx >= 0 && idx < priors.length ? priors[idx] : 0.5;
            // Weighted blend: 60% GP prior + 40% BAMCP sample
            return 0.6 * prior + 0.4 * rootBelief;
        }
        return rootBelief;
    }

    private void backpropagateWithEma(MctsOnlineNode node, double value) {
        MctsOnlineNode current = node;
        while (current != null) {
            current.updateMc(value, config.emaAlpha());
            current = current.parent;
        }
    }

    private String extractBestProfile(MctsOnlineNode root, String taskKey) {
        if (root.children.isEmpty()) return null;

        return root.children.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().visits))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private double computeConfidence(MctsOnlineNode root, String taskKey, String bestProfile) {
        if (bestProfile == null || root.visits == 0) return 0.0;
        MctsOnlineNode bestChild = root.children.get(bestProfile);
        if (bestChild == null) return 0.0;
        return (double) bestChild.visits / root.visits;
    }

    private double[] computePriors(List<String> profiles, GpPosterior posterior,
                                    float[] taskEmbedding) {
        if (posterior == null || taskEmbedding == null) {
            // Uniform prior on cold start
            double uniform = 1.0 / profiles.size();
            double[] priors = new double[profiles.size()];
            Arrays.fill(priors, uniform);
            return priors;
        }

        // Use GP predictions as priors via softmax
        GpPrediction[] predictions = new GpPrediction[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            predictions[i] = gpEngine.predict(posterior, taskEmbedding);
        }
        return bamcpSampler.softmaxPriors(predictions);
    }

    private MctsOnlineNode findChild(MctsOnlineNode root, String taskKey, String profile) {
        return root.children.get(profile);
    }

    private void updateGlobalRave(String profile, double value) {
        globalRave.compute(profile, (k, existing) -> {
            if (existing == null) {
                return new RaveEntry(value, 1);
            }
            int newVisits = existing.visits + 1;
            double newValue = existing.value + (value - existing.value) / newVisits;
            return new RaveEntry(newValue, newVisits);
        });
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    /** Global RAVE entry for a worker profile. */
    record RaveEntry(double value, int visits) {}

    /**
     * Result of MCTS online profile selection.
     *
     * @param selectedProfile  the chosen worker profile (null if no candidates)
     * @param confidence       selection confidence (visit ratio of best child)
     * @param simulationsRun   number of MCTS iterations completed
     * @param strategy         description of strategy used
     */
    public record SelectionResult(
            String selectedProfile,
            double confidence,
            int simulationsRun,
            String strategy
    ) {}
}
