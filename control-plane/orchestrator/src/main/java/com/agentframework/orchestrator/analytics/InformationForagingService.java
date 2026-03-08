package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Optimises RAG chunk retrieval using Information Foraging Theory (Pirolli &amp; Card, 1999).
 *
 * <p>The <em>Patch Foraging Model</em> (from optimal foraging theory) treats document chunks
 * as information patches. A retrieval agent exploits each patch until the marginal information
 * rate falls below the global environment average — the Marginal Value Theorem optimal policy.</p>
 *
 * <p>Key concepts:</p>
 * <ul>
 *   <li><b>Information Rate (IR)</b>: relevanceScore / retrievalCost — value per unit effort</li>
 *   <li><b>Marginal Value Theorem</b>: leave a patch when its marginal IR drops below the
 *       global mean IR minus σ × stopThresholdSigma</li>
 *   <li><b>Scent trail</b>: patches ranked by IR guide the forager toward high-value sources</li>
 * </ul>
 *
 * <p>Applied to RAG: documents with high relevance and low retrieval cost should contribute
 * more chunks; documents with IR below the stop threshold should be skipped.</p>
 */
@Service
@ConditionalOnProperty(prefix = "information-foraging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InformationForagingService {

    private static final Logger log = LoggerFactory.getLogger(InformationForagingService.class);

    @Value("${information-foraging.stop-threshold-sigma:1.0}")
    private double stopThresholdSigma;

    /**
     * Applies the Marginal Value Theorem to rank patches and recommend chunk budgets.
     *
     * @param patches list of information patches to evaluate
     * @return foraging report
     * @throws IllegalArgumentException if patches is null or empty
     */
    public ForagingReport forage(List<ForagingPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            throw new IllegalArgumentException("patches must not be null or empty");
        }

        // Compute IR per patch; guard against zero cost
        Map<String, Double> patchIR = new LinkedHashMap<>();
        for (ForagingPatch p : patches) {
            double cost = p.retrievalCost() > 0 ? p.retrievalCost() : 1.0;
            patchIR.put(p.patchId(), p.relevanceScore() / cost);
        }

        // Global mean IR
        double globalIR = patchIR.values().stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);

        // Standard deviation of IR
        double variance = patchIR.values().stream()
                .mapToDouble(ir -> Math.pow(ir - globalIR, 2))
                .average().orElse(0.0);
        double stdIR = Math.sqrt(variance);

        // Stop threshold: leave patch when IR < globalIR - σ·stopThresholdSigma
        double stopThreshold = globalIR - stopThresholdSigma * stdIR;

        // Optimal chunk budget per patch (Marginal Value Theorem)
        Map<String, Integer> optimalChunks  = new LinkedHashMap<>();
        double               totalGain      = 0.0;

        for (ForagingPatch p : patches) {
            double ir = patchIR.get(p.patchId());
            if (ir > stopThreshold) {
                double surplus     = ir - stopThreshold;
                double denominator = globalIR > 0 ? globalIR : 1.0;
                int    recommended = (int) Math.min(
                        p.chunkCount(),
                        Math.ceil(surplus * p.chunkCount() / denominator));
                recommended = Math.max(1, recommended);
                optimalChunks.put(p.patchId(), recommended);
                totalGain += p.relevanceScore() * ((double) recommended / p.chunkCount());
            } else {
                optimalChunks.put(p.patchId(), 0);
            }
        }

        // Rank patches by IR (highest first) — the "scent trail"
        List<String> patchRankings = patchIR.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        log.debug("InfoForaging: patches={} globalIR={} stopThreshold={} totalGain={}",
                patches.size(), globalIR, stopThreshold, totalGain);

        return new ForagingReport(optimalChunks, globalIR, stopThreshold, totalGain, patchRankings);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * An information patch (document or document chunk group) with retrieval metadata.
     *
     * @param patchId        unique patch identifier (document ID or chunk group ID)
     * @param relevanceScore query relevance score ∈ [0, ∞); higher = more relevant
     * @param retrievalCost  latency or computational cost to retrieve this patch (&gt; 0)
     * @param chunkCount     total number of sub-chunks available in this patch
     */
    public record ForagingPatch(
            String patchId,
            double relevanceScore,
            double retrievalCost,
            int chunkCount
    ) {}

    /**
     * Information foraging report.
     *
     * @param optimalChunks       recommended chunk budget per patch (0 = skip)
     * @param globalInformationRate mean IR = relevance/cost across all patches
     * @param stopThreshold       IR below which a patch should not be exploited
     * @param totalExpectedGain   estimated total information gain from optimal selection
     * @param patchRankings       patch IDs ordered by IR (highest first — scent trail)
     */
    public record ForagingReport(
            Map<String, Integer> optimalChunks,
            double globalInformationRate,
            double stopThreshold,
            double totalExpectedGain,
            List<String> patchRankings
    ) {}
}
