package com.agentframework.orchestrator.analytics.ids;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Information-Directed Sampling for worker dispatch.
 *
 * <p>IDS (Russo &amp; Van Roy, NeurIPS 2014 + Operations Research 2018) minimizes
 * the information ratio Ψ = regret²/information_gain. This produces optimal
 * exploration-exploitation tradeoff by selecting actions that maximize information
 * gain per unit of regret.</p>
 *
 * <p>IDS is ~10x more expensive than Thompson Sampling for GP posteriors, so
 * a TS fallback is used when insufficient data is available (&lt; minDataPoints).</p>
 *
 * <p>Information gain proxy: sigma² (posterior variance). Lower sigma² after observing
 * a worker's performance = more information gained about that worker's capabilities.</p>
 *
 * @see <a href="https://arxiv.org/abs/1402.0535">Russo &amp; Van Roy, Information-Directed Sampling (NeurIPS 2014)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.ids", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(IdsConfig.class)
public class InformationDirectedSamplingService {

    private static final Logger log = LoggerFactory.getLogger(InformationDirectedSamplingService.class);

    private final TaskOutcomeService outcomeService;
    private final IdsConfig config;

    public InformationDirectedSamplingService(TaskOutcomeService outcomeService, IdsConfig config) {
        this.outcomeService = outcomeService;
        this.config = config;
    }

    /**
     * Selects a worker type using IDS or TS fallback.
     *
     * @param item       the plan item to dispatch
     * @param candidates available worker types
     * @return IDS decision with selected worker and diagnostic metrics
     */
    public IdsDecision selectWorker(PlanItem item, List<WorkerType> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new IdsDecision(null, Double.MAX_VALUE, 0, 0, true);
        }
        if (candidates.size() == 1) {
            return new IdsDecision(candidates.get(0), 0, 0, 0, false);
        }

        float[] embedding = embedItem(item);

        // Predict for each candidate
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
            return new IdsDecision(candidates.get(0), Double.MAX_VALUE, 0, 0, true);
        }

        // Check if enough data for IDS (fallback to greedy TS-like if not)
        boolean useFallback = config.tsFallback() && predictions.values().stream()
                .allMatch(p -> p.sigma() > 0.5); // High uncertainty = insufficient data

        if (useFallback) {
            return thompsonSamplingFallback(predictions);
        }

        return idsSelect(predictions);
    }

    /**
     * Computes instantaneous regret for a candidate relative to the best prediction.
     * Δ² = (mu_best - mu_candidate)²
     */
    public double computeRegret(GpPrediction candidate, GpPrediction best) {
        double delta = best.mu() - candidate.mu();
        return config.regretWeight() * delta * delta;
    }

    /**
     * Computes information gain proxy for a candidate.
     * More uncertain candidates provide more information when observed.
     */
    public double computeInformationGain(GpPrediction prediction) {
        return prediction.sigma2(); // sigma² as proxy for information gain
    }

    /**
     * Information ratio: Ψ = regret²/information_gain.
     * IDS selects the action that minimizes this ratio.
     */
    public double informationRatio(double regretSquared, double informationGain) {
        if (informationGain < 1e-10) return Double.MAX_VALUE; // No info = infinite ratio
        return regretSquared / informationGain;
    }

    // ── IDS core ────────────────────────────────────────────────────────────

    private IdsDecision idsSelect(Map<WorkerType, GpPrediction> predictions) {
        // Find best predicted candidate (highest mu)
        GpPrediction bestPred = predictions.values().stream()
                .max(Comparator.comparingDouble(GpPrediction::mu))
                .orElse(null);

        WorkerType bestType = null;
        double bestRatio = Double.MAX_VALUE;
        double bestRegret = 0;
        double bestInfoGain = 0;

        for (var entry : predictions.entrySet()) {
            double regretSq = computeRegret(entry.getValue(), bestPred);
            double infoGain = computeInformationGain(entry.getValue());
            double ratio = informationRatio(regretSq, infoGain);

            if (ratio < bestRatio) {
                bestRatio = ratio;
                bestType = entry.getKey();
                bestRegret = regretSq;
                bestInfoGain = infoGain;
            }
        }

        log.debug("IDS selected: type={} ratio={} regret²={} infoGain={}",
                bestType, String.format("%.4f", bestRatio),
                String.format("%.4f", bestRegret), String.format("%.4f", bestInfoGain));

        return new IdsDecision(bestType, bestRatio, bestRegret, bestInfoGain, false);
    }

    // ── Thompson Sampling fallback ──────────────────────────────────────────

    private IdsDecision thompsonSamplingFallback(Map<WorkerType, GpPrediction> predictions) {
        // TS: sample from each posterior, select highest sample
        var rng = java.util.concurrent.ThreadLocalRandom.current();

        WorkerType bestType = null;
        double bestSample = Double.NEGATIVE_INFINITY;

        for (var entry : predictions.entrySet()) {
            GpPrediction pred = entry.getValue();
            double sample = pred.mu() + pred.sigma() * rng.nextGaussian();
            if (sample > bestSample) {
                bestSample = sample;
                bestType = entry.getKey();
            }
        }

        log.debug("IDS fallback to TS: selected {} (sample={})", bestType, String.format("%.4f", bestSample));
        return new IdsDecision(bestType, Double.MAX_VALUE, 0, 0, true);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private float[] embedItem(PlanItem item) {
        String desc = item.getDescription() != null ? item.getDescription() : "";
        return outcomeService.embedTask(desc, desc);
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * IDS worker selection decision.
     *
     * @param selected          chosen worker type
     * @param informationRatio  Ψ = regret²/info_gain (lower = more efficient exploration)
     * @param regretSquared     squared instantaneous regret vs best candidate
     * @param informationGain   information gain proxy (sigma²) for selected candidate
     * @param usedTsFallback    true if Thompson Sampling was used instead of IDS
     */
    public record IdsDecision(
            WorkerType selected,
            double informationRatio,
            double regretSquared,
            double informationGain,
            boolean usedTsFallback
    ) {}
}
