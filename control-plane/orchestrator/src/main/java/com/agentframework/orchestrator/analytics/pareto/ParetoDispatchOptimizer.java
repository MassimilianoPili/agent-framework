package com.agentframework.orchestrator.analytics.pareto;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GP-guided cascade optimizer for cost-quality tradeoff in worker dispatch.
 *
 * <p>Implements the pragmatic approach validated by FrugalGPT (Chen et al. 2023)
 * and RouteLLM (Ong et al. 2024): a simple cascade captures ~80% of the cost savings
 * with minimal complexity. The cascade logic:</p>
 * <ol>
 *   <li>GP predict (mu, sigma2) for each candidate worker</li>
 *   <li>If mu &gt; qualityThreshold AND sigma &lt; sigmaThreshold → choose cheapest (cost-efficient)</li>
 *   <li>Otherwise → escalate to highest-mu candidate (quality-first)</li>
 * </ol>
 *
 * <p>Also supports Pareto dominance analysis for multi-objective scenarios
 * (Sener &amp; Koltun, NeurIPS 2018).</p>
 *
 * @see <a href="https://arxiv.org/abs/2305.05062">Chen et al., FrugalGPT (2023)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.pareto", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ParetoConfig.class)
public class ParetoDispatchOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ParetoDispatchOptimizer.class);

    private final TaskOutcomeService outcomeService;
    private final ParetoConfig config;

    public ParetoDispatchOptimizer(TaskOutcomeService outcomeService, ParetoConfig config) {
        this.outcomeService = outcomeService;
        this.config = config;
    }

    /**
     * Optimizes worker selection for cost-quality balance.
     *
     * @param item       the plan item to dispatch
     * @param candidates available worker types to choose from
     * @return optimization decision with expected quality, cost, and explanation
     */
    public ParetoDecision optimize(PlanItem item, List<WorkerType> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ParetoDecision(null, 0.0, 0.0, false, "no candidates");
        }
        if (candidates.size() == 1) {
            return new ParetoDecision(candidates.get(0), 0.0, 0.0, false, "single candidate");
        }

        // Predict for each candidate
        float[] embedding = embedItem(item);
        Map<WorkerType, GpPrediction> predictions = new LinkedHashMap<>();
        for (WorkerType wt : candidates) {
            try {
                GpPrediction pred = outcomeService.predict(embedding, wt.name(), wt.name().toLowerCase());
                predictions.put(wt, pred);
            } catch (Exception e) {
                log.trace("GP prediction failed for {}: {}", wt, e.getMessage());
            }
        }

        if (predictions.isEmpty()) {
            return new ParetoDecision(candidates.get(0), 0.0, 0.0, false, "GP unavailable, fallback");
        }

        if (config.cascadeEnabled()) {
            return cascadeOptimize(predictions, item);
        } else {
            return paretoOptimize(predictions, item);
        }
    }

    /**
     * Checks if cost profile A is Pareto-dominated by cost profile B.
     * A is dominated if B is at least as good in all objectives and strictly better in at least one.
     */
    public boolean isDominated(CostProfile a, CostProfile b) {
        boolean betterOrEqual = b.avgQuality() >= a.avgQuality() && b.avgCostUsd() <= a.avgCostUsd();
        boolean strictlyBetter = b.avgQuality() > a.avgQuality() || b.avgCostUsd() < a.avgCostUsd();
        return betterOrEqual && strictlyBetter;
    }

    // ── Cascade strategy ────────────────────────────────────────────────────

    private ParetoDecision cascadeOptimize(Map<WorkerType, GpPrediction> predictions, PlanItem item) {
        // Check if all candidates are "confident enough" for cost-efficient mode
        boolean allConfident = predictions.values().stream()
                .allMatch(p -> p.mu() > config.qualityThreshold() && p.sigma() < config.sigmaThreshold());

        if (allConfident) {
            // Cost-efficient: pick cheapest candidate (by estimated cost, or first available)
            WorkerType cheapest = selectCheapest(predictions.keySet(), item);
            GpPrediction pred = predictions.get(cheapest);
            return new ParetoDecision(cheapest, pred.mu(), estimateCost(cheapest, item),
                    false, String.format("cascade:cost-efficient (mu=%.3f, sigma=%.3f)", pred.mu(), pred.sigma()));
        }

        // Quality-first: escalate to highest-mu candidate
        Map.Entry<WorkerType, GpPrediction> best = predictions.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().mu()))
                .orElse(predictions.entrySet().iterator().next());

        return new ParetoDecision(best.getKey(), best.getValue().mu(), estimateCost(best.getKey(), item),
                true, String.format("cascade:quality-first (mu=%.3f, sigma=%.3f)", best.getValue().mu(), best.getValue().sigma()));
    }

    // ── Pareto strategy ─────────────────────────────────────────────────────

    private ParetoDecision paretoOptimize(Map<WorkerType, GpPrediction> predictions, PlanItem item) {
        // Weighted objective: (1-costWeight) * quality - costWeight * normalized_cost
        double qualityWeight = 1.0 - config.costWeight();

        WorkerType bestType = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (var entry : predictions.entrySet()) {
            double quality = entry.getValue().mu();
            double cost = estimateCost(entry.getKey(), item);
            double score = qualityWeight * quality - config.costWeight() * cost;

            if (score > bestScore) {
                bestScore = score;
                bestType = entry.getKey();
            }
        }

        GpPrediction bestPred = predictions.get(bestType);
        return new ParetoDecision(bestType, bestPred.mu(), estimateCost(bestType, item),
                false, String.format("pareto:weighted (score=%.3f, w_cost=%.1f)", bestScore, config.costWeight()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private float[] embedItem(PlanItem item) {
        String desc = item.getDescription() != null ? item.getDescription() : "";
        return outcomeService.embedTask(desc, desc);
    }

    private WorkerType selectCheapest(Set<WorkerType> candidates, PlanItem item) {
        // Heuristic cost ordering: smaller ordinal = cheaper (BE < FE < AI_TASK < etc.)
        return candidates.stream()
                .min(Comparator.comparingInt(WorkerType::ordinal))
                .orElse(candidates.iterator().next());
    }

    private double estimateCost(WorkerType type, PlanItem item) {
        if (item.getEstimatedCostUsd() != null) {
            return item.getEstimatedCostUsd().doubleValue();
        }
        // Fallback: rough estimate based on worker type complexity
        return switch (type) {
            case BE, FE, DBA -> 0.01;
            case AI_TASK, REVIEW -> 0.05;
            case MANAGER, COUNCIL_MANAGER -> 0.10;
            default -> 0.02;
        };
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Pareto optimization decision.
     *
     * @param recommended     recommended worker type (null if no candidates)
     * @param expectedQuality GP posterior mu for recommended worker
     * @param expectedCost    estimated cost in USD
     * @param escalated       true if cascade escalated to quality-first mode
     * @param reason          human-readable optimization rationale
     */
    public record ParetoDecision(
            WorkerType recommended,
            double expectedQuality,
            double expectedCost,
            boolean escalated,
            String reason
    ) {}

    /**
     * Historical cost/quality profile for a worker type.
     *
     * @param type              worker type
     * @param avgCostUsd        average cost per task (USD)
     * @param avgQuality        average quality (reward) per task
     * @param costPerQualityUnit cost efficiency: avgCostUsd / avgQuality
     */
    public record CostProfile(
            WorkerType type,
            double avgCostUsd,
            double avgQuality,
            double costPerQualityUnit
    ) {}
}
