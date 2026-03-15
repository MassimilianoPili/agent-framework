package com.agentframework.orchestrator.analytics.sycophancy;

import com.agentframework.orchestrator.analytics.CouncilDiversityService;
import com.agentframework.orchestrator.analytics.VotingProtocolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects sycophancy in Council deliberations using 6 complementary signals.
 *
 * <p>Sycophancy — the tendency to agree with or mirror others rather than provide
 * independent judgment — is a well-documented failure mode in multi-agent systems.
 * This service implements detection based on:</p>
 * <ul>
 *   <li>CONSENSAGENT (Pitre et al. ACL Findings 2025)</li>
 *   <li>Sharma et al. (ICLR 2024, foundational, 3 types separable in latent space)</li>
 *   <li>Vennemeyer et al. (arXiv:2509.21305, sycophancy-calibration link)</li>
 * </ul>
 *
 * <p>Design corrections from research synthesis:</p>
 * <ul>
 *   <li>Cosine threshold 0.85-0.90 (not 0.95 — too strict, misses sycophancy)</li>
 *   <li>Reasoning diversity collapse (justification similarity, not just vote similarity)</li>
 *   <li>First-mover anchoring (Zhu et al. ACL 2025 main track)</li>
 *   <li>Devil's advocate must be collaborative not adversarial (ColMAD, Chen et al. 2025)</li>
 * </ul>
 *
 * @see <a href="https://arxiv.org/abs/2310.13548">Sharma et al., Sycophancy in LLMs (ICLR 2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.sycophancy", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(SycophancyConfig.class)
public class SycophancyDetectorService {

    private static final Logger log = LoggerFactory.getLogger(SycophancyDetectorService.class);

    private final Optional<CouncilDiversityService> diversityService;
    private final SycophancyConfig config;

    public SycophancyDetectorService(Optional<CouncilDiversityService> diversityService,
                                      SycophancyConfig config) {
        this.diversityService = diversityService;
        this.config = config;
    }

    /**
     * Analyzes Council member outputs for sycophancy across 6 signals.
     *
     * @param memberOutputs  list of textual outputs from each council member
     * @param diversityReport optional pre-computed diversity report (from CouncilDiversityService)
     * @return sycophancy report with aggregated score and triggered signals
     */
    public SycophancyReport analyze(List<String> memberOutputs,
                                     CouncilDiversityService.DiversityReport diversityReport) {
        if (memberOutputs == null || memberOutputs.size() < 2) {
            return new SycophancyReport(0.0, List.of(), false, "insufficient members for analysis");
        }

        List<Signal> signals = new ArrayList<>();

        // Signal 1: Entropy collapse
        if (diversityReport != null) {
            double entropy = diversityReport.normalizedEntropy();
            boolean triggered = entropy < config.entropyThreshold();
            signals.add(new Signal("entropy_collapse", entropy, config.entropyThreshold(),
                    triggered, true)); // inverted: lower = worse

            // Signal 3: Kendall tau (ranking similarity)
            double tau = diversityReport.avgKendallTau();
            boolean tauTriggered = tau > config.kendallThreshold();
            signals.add(new Signal("kendall_tau", tau, config.kendallThreshold(),
                    tauTriggered, false));

            // Signal 6: Redundant members (echo chamber)
            int redundant = diversityReport.redundantMembers() != null
                    ? diversityReport.redundantMembers().size() : 0;
            double redundancyRatio = memberOutputs.size() > 0
                    ? (double) redundant / memberOutputs.size() : 0.0;
            boolean redundancyTriggered = redundancyRatio > 0.2;
            signals.add(new Signal("redundancy_ratio", redundancyRatio, 0.2,
                    redundancyTriggered, false));
        }

        // Signal 2: Pairwise cosine similarity of outputs
        double avgCosine = computeAveragePairwiseSimilarity(memberOutputs);
        boolean cosineTriggered = avgCosine > config.cosineLower();
        signals.add(new Signal("cosine_similarity", avgCosine, config.cosineLower(),
                cosineTriggered, false));

        // Signal 4: Reasoning diversity collapse (length variance as proxy)
        double lengthVariance = computeLengthVariance(memberOutputs);
        double normalizedLengthVar = Math.min(1.0, lengthVariance / 10000.0); // normalize
        boolean reasoningTriggered = normalizedLengthVar < 0.1; // very similar lengths
        signals.add(new Signal("reasoning_diversity", normalizedLengthVar, 0.1,
                reasoningTriggered, true)); // inverted

        // Signal 5: First-mover anchoring (similarity to first response)
        double anchoringScore = computeFirstMoverAnchoring(memberOutputs);
        boolean anchoringTriggered = anchoringScore > config.cosineUpper();
        signals.add(new Signal("first_mover_anchoring", anchoringScore, config.cosineUpper(),
                anchoringTriggered, false));

        // Aggregate: weighted mean of triggered signals
        double sycophancyScore = computeAggregateScore(signals);
        boolean requiresIntervention = sycophancyScore > 0.5;

        String recommendation = requiresIntervention
                ? "High sycophancy detected: consider re-running council with collaborative devil's advocate"
                : "Sycophancy within acceptable bounds";

        log.debug("Sycophancy analysis: score={} triggered={}/{} intervention={}",
                String.format("%.3f", sycophancyScore),
                signals.stream().filter(Signal::triggered).count(), signals.size(),
                requiresIntervention);

        return new SycophancyReport(sycophancyScore, signals, requiresIntervention, recommendation);
    }

    // ── Similarity computation ──────────────────────────────────────────────

    private double computeAveragePairwiseSimilarity(List<String> outputs) {
        if (outputs.size() < 2) return 0.0;

        double totalSim = 0.0;
        int pairs = 0;
        for (int i = 0; i < outputs.size(); i++) {
            for (int j = i + 1; j < outputs.size(); j++) {
                totalSim += textSimilarity(outputs.get(i), outputs.get(j));
                pairs++;
            }
        }
        return pairs > 0 ? totalSim / pairs : 0.0;
    }

    private double computeFirstMoverAnchoring(List<String> outputs) {
        if (outputs.size() < 2) return 0.0;

        String first = outputs.get(0);
        int window = Math.min(config.anchoringWindow(), outputs.size());

        double totalSim = 0.0;
        for (int i = 1; i < window; i++) {
            totalSim += textSimilarity(first, outputs.get(i));
        }
        return window > 1 ? totalSim / (window - 1) : 0.0;
    }

    private double computeLengthVariance(List<String> outputs) {
        double mean = outputs.stream().mapToInt(String::length).average().orElse(0.0);
        return outputs.stream()
                .mapToDouble(s -> Math.pow(s.length() - mean, 2))
                .average()
                .orElse(0.0);
    }

    /**
     * Text similarity using character n-gram overlap (Jaccard on trigrams).
     * Lightweight alternative to embedding-based similarity — no Ollama dependency.
     */
    private double textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;

        var trigramsA = extractTrigrams(a.toLowerCase());
        var trigramsB = extractTrigrams(b.toLowerCase());

        long intersection = trigramsA.stream().filter(trigramsB::contains).count();
        long union = trigramsA.size() + trigramsB.size() - intersection;

        return union > 0 ? (double) intersection / union : 0.0;
    }

    private java.util.Set<String> extractTrigrams(String text) {
        var trigrams = new java.util.HashSet<String>();
        for (int i = 0; i <= text.length() - 3; i++) {
            trigrams.add(text.substring(i, i + 3));
        }
        return trigrams;
    }

    private double computeAggregateScore(List<Signal> signals) {
        if (signals.isEmpty()) return 0.0;

        double sum = 0.0;
        for (Signal s : signals) {
            if (s.triggered()) {
                // For inverted signals (lower = worse), normalize differently
                if (s.inverted()) {
                    sum += Math.max(0, 1.0 - s.value() / s.threshold());
                } else {
                    sum += Math.min(1.0, s.value() / Math.max(s.threshold(), 1e-6));
                }
            }
        }
        return sum / signals.size();
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Sycophancy analysis report.
     *
     * @param sycophancyScore      aggregate sycophancy score [0, 1] (higher = more sycophantic)
     * @param activeSignals        individual signal evaluations
     * @param requiresIntervention true if sycophancyScore exceeds intervention threshold
     * @param recommendation       human-readable recommendation
     */
    public record SycophancyReport(
            double sycophancyScore,
            List<Signal> activeSignals,
            boolean requiresIntervention,
            String recommendation
    ) {}

    /**
     * Individual sycophancy signal evaluation.
     *
     * @param name      signal identifier
     * @param value     measured value
     * @param threshold trigger threshold
     * @param triggered whether this signal was activated
     * @param inverted  true if lower values indicate sycophancy (e.g. entropy)
     */
    public record Signal(
            String name,
            double value,
            double threshold,
            boolean triggered,
            boolean inverted
    ) {}
}
