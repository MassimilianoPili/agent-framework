package com.agentframework.orchestrator.optimizer;

import com.agentframework.orchestrator.knowledge.CrossPlanKnowledgeEngine;
import com.agentframework.orchestrator.optimizer.CanaryEvaluator.CanaryDecision;
import com.agentframework.orchestrator.optimizer.CanaryEvaluator.CanaryResult;
import com.agentframework.orchestrator.optimizer.PromptEvolutionEngine.PromptVariant;
import com.agentframework.orchestrator.optimizer.StrategyBOHBOptimizer.BOHBResult;
import com.agentframework.orchestrator.optimizer.StrategyBOHBOptimizer.StrategyConfig;
import com.agentframework.orchestrator.optimizer.StrategyBOHBOptimizer.StrategySearchSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-Improving Prompt and Strategy Optimizer.
 *
 * <p>Orchestrates evolutionary prompt optimization + BOHB strategy HPO with
 * canary-based safe deployment. Implements a closed loop:</p>
 * <ol>
 *   <li><b>Evolve</b>: generate new prompt variants via {@link PromptEvolutionEngine}</li>
 *   <li><b>Evaluate</b>: score variants using PRM + diversity + safety</li>
 *   <li><b>Canary</b>: deploy best candidate on 10% of tasks</li>
 *   <li><b>Promote/Rollback</b>: promote if p&lt;0.05, rollback if regression</li>
 * </ol>
 *
 * <p>5 safety guardrails: mode collapse (diversity penalty), reward hacking
 * (multi-objective), prompt bloat (max length), safety drift (hard constraint),
 * regression (canary + golden suite).</p>
 *
 * @see <a href="https://arxiv.org/abs/2309.08532">EvoPrompt (Guo et al., ICLR 2024)</a>
 * @see <a href="https://arxiv.org/abs/1807.01774">BOHB (Falkner et al., ICML 2018)</a>
 * @see <a href="https://arxiv.org/abs/2309.16797">Promptbreeder (Fernando et al., ICML 2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "self-improving", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SelfImprovingConfig.class)
public class SelfImprovingOptimizerService {

    private static final Logger log = LoggerFactory.getLogger(SelfImprovingOptimizerService.class);

    private final PromptEvolutionEngine evolutionEngine;
    private final StrategyBOHBOptimizer bohbOptimizer;
    private final CanaryEvaluator canaryEvaluator;
    private final EvolutionLineageTracker lineageTracker;
    private final SelfImprovingConfig config;
    @Nullable private final CrossPlanKnowledgeEngine knowledgeEngine;

    /** Active canary variant per worker type. */
    private final ConcurrentHashMap<String, UUID> activeCanaries = new ConcurrentHashMap<>();

    /** Current best strategy config. */
    private volatile StrategyConfig currentStrategy;

    public SelfImprovingOptimizerService(PromptEvolutionEngine evolutionEngine,
                                           StrategyBOHBOptimizer bohbOptimizer,
                                           CanaryEvaluator canaryEvaluator,
                                           EvolutionLineageTracker lineageTracker,
                                           SelfImprovingConfig config,
                                           @Nullable CrossPlanKnowledgeEngine knowledgeEngine) {
        this.evolutionEngine = evolutionEngine;
        this.bohbOptimizer = bohbOptimizer;
        this.canaryEvaluator = canaryEvaluator;
        this.lineageTracker = lineageTracker;
        this.config = config;
        this.knowledgeEngine = knowledgeEngine;
    }

    /**
     * Runs one evolution cycle for a worker type.
     *
     * <p>Steps: evolve population → track lineage → select canary candidate.</p>
     *
     * @param workerType   worker type
     * @param generation   current generation number
     * @param fitnessScores variant UUID → fitness score map
     * @return evolution cycle result
     */
    public EvolutionCycleResult runEvolutionCycle(String workerType, int generation,
                                                    Map<UUID, Double> fitnessScores) {
        // Evolve
        List<PromptVariant> nextGen = evolutionEngine.evolveGeneration(workerType, generation, fitnessScores);

        // Track all variants
        for (PromptVariant variant : nextGen) {
            lineageTracker.track(variant);
        }

        // Select canary candidate (best of generation)
        Optional<PromptVariant> best = evolutionEngine.getBest(workerType);
        UUID canaryId = null;
        if (best.isPresent()) {
            canaryId = best.get().id();
            activeCanaries.put(workerType, canaryId);
            log.debug("Canary candidate for {}: {} (fitness={})",
                    workerType, canaryId, best.get().fitnessScore());
        }

        return new EvolutionCycleResult(workerType, generation, nextGen.size(),
                best.map(PromptVariant::fitnessScore).orElse(0.0), canaryId);
    }

    /**
     * Initializes the optimizer for a worker type with seed prompts.
     *
     * <p>Seeds can come from CrossPlanKnowledgeEngine (best historical prompts)
     * or from default prompt templates.</p>
     *
     * @param workerType worker type
     * @param seeds      initial prompt variants
     */
    public void initialize(String workerType, List<String> seeds) {
        evolutionEngine.initializePopulation(workerType, seeds);
        log.info("Initialized optimizer for {} with {} seeds", workerType, seeds.size());
    }

    /**
     * Gets the prompt to use for a task, considering canary deployment.
     *
     * @param workerType worker type
     * @param taskId     task UUID (for canary assignment)
     * @return the prompt to use, or null if no optimization available
     */
    @Nullable
    public String getPromptForTask(String workerType, UUID taskId) {
        UUID canaryId = activeCanaries.get(workerType);
        if (canaryId == null) return null;

        // Check if this task is in the canary cohort
        if (canaryEvaluator.shouldUseCanary(taskId)) {
            Optional<PromptVariant> variant = evolutionEngine.getPopulation(workerType).stream()
                    .filter(v -> v.id().equals(canaryId))
                    .findFirst();
            return variant.map(PromptVariant::promptContent).orElse(null);
        }

        // Non-canary: use last-known-good
        PromptVariant lastGood = lineageTracker.findLastKnownGood(workerType);
        return lastGood != null ? lastGood.promptContent() : null;
    }

    /**
     * Records a task outcome for canary evaluation.
     */
    public void recordTaskOutcome(String workerType, UUID taskId, boolean success, double prmScore) {
        UUID canaryId = activeCanaries.get(workerType);
        if (canaryId != null && canaryEvaluator.shouldUseCanary(taskId)) {
            canaryEvaluator.recordObservation(canaryId, taskId, success, prmScore);
        }
    }

    /**
     * Evaluates the current canary and decides: promote, rollback, or continue.
     *
     * @param workerType   worker type
     * @param baselineRate current baseline success rate
     * @return canary evaluation result
     */
    public CanaryResult evaluateCanary(String workerType, double baselineRate) {
        UUID canaryId = activeCanaries.get(workerType);
        if (canaryId == null) {
            return new CanaryResult(null, 0, 0.0, 1.0, CanaryDecision.INSUFFICIENT_DATA);
        }

        CanaryResult result = canaryEvaluator.evaluate(canaryId, baselineRate);

        switch (result.decision()) {
            case PROMOTE -> {
                canaryEvaluator.finalize(canaryId, true);
                activeCanaries.remove(workerType);
                log.info("Promoted canary {} for {} (successRate={}, p={})",
                        canaryId, workerType, result.successRate(), result.pValue());
            }
            case ROLLBACK -> {
                canaryEvaluator.finalize(canaryId, false);
                activeCanaries.remove(workerType);
                log.warn("Rolled back canary {} for {} (successRate={} < baseline={})",
                        canaryId, workerType, result.successRate(), baselineRate);
            }
            default -> log.debug("Canary {} for {}: {} (n={}, rate={})",
                    canaryId, workerType, result.decision(), result.taskCount(), result.successRate());
        }

        return result;
    }

    /**
     * Runs BOHB strategy optimization.
     *
     * @param scorer function to evaluate strategy configurations
     * @return optimization result
     */
    public BOHBResult optimizeStrategy(StrategyBOHBOptimizer.StrategyScorer scorer) {
        StrategySearchSpace space = new StrategySearchSpace(5, 4.0);
        BOHBResult result = bohbOptimizer.optimize(space, scorer);

        if (result.bestConfig() != null) {
            currentStrategy = result.bestConfig();
            log.info("BOHB optimized strategy: {} (score={})", result.bestConfig(), result.bestScore());
        }

        return result;
    }

    /**
     * Returns the current optimized strategy, or null if not yet optimized.
     */
    @Nullable
    public StrategyConfig getCurrentStrategy() {
        return currentStrategy;
    }

    /**
     * Returns optimizer status for monitoring endpoints.
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeCanaries", activeCanaries.size());
        status.put("canaryWorkerTypes", new ArrayList<>(activeCanaries.keySet()));
        status.put("currentStrategy", currentStrategy);

        Map<String, Object> populations = new LinkedHashMap<>();
        for (String wt : activeCanaries.keySet()) {
            populations.put(wt, Map.of(
                    "size", evolutionEngine.getPopulation(wt).size(),
                    "bestFitness", evolutionEngine.getBest(wt)
                            .map(PromptVariant::fitnessScore).orElse(0.0),
                    "mutationEffectiveness", lineageTracker.mutationEffectiveness(wt)));
        }
        status.put("populations", populations);

        return status;
    }

    // --- Types ---

    /**
     * Result of one evolution cycle.
     */
    public record EvolutionCycleResult(
            String workerType,
            int generation,
            int populationSize,
            double bestFitness,
            @Nullable UUID canaryId
    ) {}
}
