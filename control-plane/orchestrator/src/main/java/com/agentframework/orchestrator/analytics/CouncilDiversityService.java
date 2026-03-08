package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Quantifies cognitive diversity of a council using Social Systems Theory and
 * Scott &amp; Page's Diversity Prediction Theorem (2007).
 *
 * <p>The Diversity Prediction Theorem states:
 * <pre>
 *   Crowd Error = Average Individual Error − Diversity
 * </pre>
 * where <em>Diversity</em> is the variance of individual predictions around the
 * crowd's consensus. High ballot diversity is therefore a structural asset —
 * it reduces collective error even when individual member accuracy is constant.
 *
 * <p>Metrics computed:
 * <ol>
 *   <li><b>Shannon entropy</b>: H = −Σ pᵢ·ln(pᵢ) over the first-choice distribution.
 *       Maximum H = ln(|candidates|) when all candidates receive equal first-place votes.</li>
 *   <li><b>Normalised entropy</b>: H / ln(|candidates|) ∈ [0,1].
 *       1.0 = maximally diverse; 0.0 = unanimous.</li>
 *   <li><b>Kendall τ</b>: pairwise rank-order correlation between ballots.
 *       τ ≈ 1 → redundant council; τ ≈ 0 → cognitively independent; τ ≈ −1 → opposing.</li>
 *   <li><b>Scott–Page diversity score</b>: average variance of candidate rank positions
 *       across ballots — directly implements the "diversity" term in the DPT.</li>
 *   <li><b>Recommended council size</b>: smallest k such that adding member k+1 yields
 *       marginal entropy gain &lt; 0.01 nats.</li>
 *   <li><b>Redundant members</b>: voters whose removal changes entropy by &lt; 1%.</li>
 * </ol>
 *
 * @see <a href="https://doi.org/10.1515/9781400830282">
 *     Scott E. Page, The Difference (2007) — Diversity Prediction Theorem</a>
 */
@Service
@ConditionalOnProperty(prefix = "council-diversity", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CouncilDiversityService {

    private static final Logger log = LoggerFactory.getLogger(CouncilDiversityService.class);

    /**
     * Analyzes cognitive diversity of the council from their ranked ballots.
     *
     * @param ballots ranked ballots from each council member (non-null, non-empty)
     * @return diversity report, or {@code null} if the ballot list is empty
     */
    public DiversityReport analyze(List<VotingProtocolService.RankedBallot> ballots) {
        if (ballots == null || ballots.isEmpty()) {
            return null;
        }

        List<String> candidates = new ArrayList<>(ballots.get(0).ranking());
        int n = ballots.size();
        int c = candidates.size();

        // ── Shannon entropy of first-choice distribution ──────────────────────
        Map<String, Integer> firstChoiceCounts = new LinkedHashMap<>();
        candidates.forEach(cand -> firstChoiceCounts.put(cand, 0));
        for (VotingProtocolService.RankedBallot b : ballots) {
            if (!b.ranking().isEmpty()) {
                firstChoiceCounts.merge(b.ranking().get(0), 1, Integer::sum);
            }
        }

        double shannonEntropy = computeEntropy(firstChoiceCounts, n);
        double maxEntropy = c > 1 ? Math.log(c) : 1.0;
        double normalizedEntropy = maxEntropy > 0 ? shannonEntropy / maxEntropy : 0.0;

        // ── Scott & Page diversity: average variance of rank positions ─────────
        // Each ballot encodes candidate ranking as position index (0 = best).
        // diversity = mean across candidates of rank-position variance across voters.
        double diversityScore = computePositionalVariance(ballots, candidates, c);

        // ── Kendall τ: mean pairwise rank correlation ──────────────────────────
        double avgKendallTau = computeAvgKendallTau(ballots, candidates);

        // ── Recommended council size (marginal entropy gain threshold) ─────────
        int recommendedSize = computeRecommendedSize(ballots);

        // ── Redundant members (removal < 1% entropy loss) ─────────────────────
        List<String> redundantMembers = findRedundantMembers(ballots, shannonEntropy);

        log.debug("CouncilDiversity n={} H={} (norm={}) tau={} diversity={}",
                n, shannonEntropy, normalizedEntropy, avgKendallTau, diversityScore);

        return new DiversityReport(
                shannonEntropy, normalizedEntropy, avgKendallTau,
                diversityScore, recommendedSize, redundantMembers);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private double computeEntropy(Map<String, Integer> counts, int total) {
        double h = 0.0;
        for (int cnt : counts.values()) {
            if (cnt > 0) {
                double p = (double) cnt / total;
                h -= p * Math.log(p);
            }
        }
        return h;
    }

    private double computePositionalVariance(List<VotingProtocolService.RankedBallot> ballots,
                                              List<String> candidates, int c) {
        int n = ballots.size();
        double totalVariance = 0.0;
        for (String cand : candidates) {
            double[] ranks = new double[n];
            for (int i = 0; i < n; i++) {
                int pos = ballots.get(i).ranking().indexOf(cand);
                ranks[i] = pos >= 0 ? pos : c; // unranked → pushed to last position
            }
            double mean = Arrays.stream(ranks).average().orElse(0.0);
            double variance = Arrays.stream(ranks)
                    .map(r -> (r - mean) * (r - mean))
                    .average().orElse(0.0);
            totalVariance += variance;
        }
        return c > 0 ? totalVariance / c : 0.0;
    }

    private double computeAvgKendallTau(List<VotingProtocolService.RankedBallot> ballots,
                                         List<String> candidates) {
        if (ballots.size() < 2) return 1.0;
        double sumTau = 0.0;
        int pairs = 0;
        for (int i = 0; i < ballots.size(); i++) {
            for (int j = i + 1; j < ballots.size(); j++) {
                sumTau += kendallTau(ballots.get(i).ranking(),
                                     ballots.get(j).ranking(), candidates);
                pairs++;
            }
        }
        return pairs > 0 ? sumTau / pairs : 0.0;
    }

    /**
     * Kendall τ between two rankings: τ = (concordant − discordant) / C(c, 2).
     * τ = 1 → identical; τ = −1 → reversed; τ ≈ 0 → uncorrelated (diverse).
     */
    private double kendallTau(List<String> r1, List<String> r2, List<String> candidates) {
        int concordant = 0, discordant = 0;
        int c = candidates.size();
        for (int i = 0; i < c; i++) {
            for (int j = i + 1; j < c; j++) {
                String a = candidates.get(i), b = candidates.get(j);
                int dir1 = Integer.compare(r1.indexOf(a), r1.indexOf(b));
                int dir2 = Integer.compare(r2.indexOf(a), r2.indexOf(b));
                if (dir1 != 0 && dir2 != 0) {
                    if (dir1 == dir2) concordant++;
                    else discordant++;
                }
            }
        }
        int total = concordant + discordant;
        return total > 0 ? (double) (concordant - discordant) / total : 0.0;
    }

    /** Returns k such that adding voter k+1 yields marginal entropy gain &lt; 0.01 nats. */
    private int computeRecommendedSize(List<VotingProtocolService.RankedBallot> ballots) {
        if (ballots.size() <= 1) return ballots.size();
        double prevH = 0.0;
        for (int k = 1; k <= ballots.size(); k++) {
            double h = shannonEntropyOfFirstChoices(ballots.subList(0, k));
            if (k > 1 && (h - prevH) < 0.01) {
                return k - 1;
            }
            prevH = h;
        }
        return ballots.size();
    }

    private double shannonEntropyOfFirstChoices(List<VotingProtocolService.RankedBallot> subset) {
        Map<String, Integer> counts = new HashMap<>();
        for (var b : subset) {
            if (!b.ranking().isEmpty()) counts.merge(b.ranking().get(0), 1, Integer::sum);
        }
        int n = subset.size();
        double h = 0.0;
        for (int cnt : counts.values()) {
            double p = (double) cnt / n;
            if (p > 0) h -= p * Math.log(p);
        }
        return h;
    }

    /** Members whose removal changes entropy by &lt; 1% of the baseline. */
    private List<String> findRedundantMembers(List<VotingProtocolService.RankedBallot> ballots,
                                               double baseEntropy) {
        List<String> redundant = new ArrayList<>();
        if (baseEntropy < 1e-9) return redundant; // unanimous — everyone is "redundant" except one
        for (int i = 0; i < ballots.size(); i++) {
            List<VotingProtocolService.RankedBallot> without = new ArrayList<>(ballots);
            without.remove(i);
            double hWithout = shannonEntropyOfFirstChoices(without);
            if (Math.abs(baseEntropy - hWithout) < 0.01 * baseEntropy) {
                redundant.add(ballots.get(i).voterId());
            }
        }
        return redundant;
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    /**
     * Result of diversity analysis over a set of ranked ballots.
     *
     * @param shannonEntropy     H = −Σ pᵢ·ln(pᵢ) of first-choice distribution (nats)
     * @param normalizedEntropy  H / ln(|candidates|) ∈ [0, 1]; 1.0 = maximally diverse
     * @param avgKendallTau      mean pairwise Kendall τ; near 0 = independent, 1 = unanimous
     * @param diversityScore     Scott–Page positional variance score; higher = more diverse
     * @param recommendedSize    optimal council size (marginal entropy gain threshold 0.01 nats)
     * @param redundantMembers   voter IDs whose removal changes entropy &lt; 1%
     */
    public record DiversityReport(
            double shannonEntropy,
            double normalizedEntropy,
            double avgKendallTau,
            double diversityScore,
            int recommendedSize,
            List<String> redundantMembers
    ) {}
}
