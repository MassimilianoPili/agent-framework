package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Aggregates ranked preferences from council members using Social Choice voting protocols.
 *
 * <p>Social Choice Theory (Arrow 1951) studies how individual preference orderings can be
 * aggregated into a collective decision. Arrow's Impossibility Theorem proves that no
 * deterministic voting rule with ≥ 3 candidates satisfies all three of:
 * <ul>
 *   <li>Pareto efficiency (unanimous preferences are respected)</li>
 *   <li>Independence of Irrelevant Alternatives (IIA)</li>
 *   <li>Non-dictatorship (no single voter always determines the outcome)</li>
 * </ul>
 * Each method below makes a different trade-off.
 *
 * <p>Four voting schemes are implemented:
 * <ol>
 *   <li><b>Plurality</b>: each voter casts one vote for their top choice. Fast, but ignores
 *       lower preferences and is most susceptible to the spoiler effect.</li>
 *   <li><b>Borda Count</b>: voters rank all candidates; candidate at position k receives
 *       (C − k) points (C = total candidates). Tends to favour consensus candidates.</li>
 *   <li><b>Schulze (Condorcet/Beatpath)</b>: computes the strongest pairwise preference paths
 *       using Floyd-Warshall. The Condorcet winner (if one exists) always wins.
 *       Satisfies Condorcet criterion but not IIA.</li>
 *   <li><b>Approval Voting</b>: each voter approves a fixed top-k fraction of candidates.
 *       Simple and robust; does not require a full ranking.</li>
 * </ol>
 *
 * @see <a href="https://doi.org/10.2307/1907435">
 *     Arrow (1951), Social Choice and Individual Values</a>
 * @see <a href="https://doi.org/10.1007/BF00179998">
 *     Schulze (2011), A New Monotonic, Clone-Independent, Reversal Symmetric,
 *     and Condorcet-Consistent Single-Winner Election Method</a>
 */
@Service
@ConditionalOnProperty(prefix = "social-choice", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VotingProtocolService {

    private static final Logger log = LoggerFactory.getLogger(VotingProtocolService.class);

    /** Fraction of candidates that each voter approves in Approval Voting. */
    private static final double APPROVAL_THRESHOLD = 0.5;

    @Value("${social-choice.default-method:SCHULZE}")
    private String defaultMethod;

    /**
     * Aggregates preferences using the specified voting method.
     *
     * @param ballots list of ranked ballots (each ballot orders all candidates)
     * @param method  voting method to apply
     * @return aggregated voting result
     * @throws IllegalArgumentException if ballots are empty
     */
    public VotingResult aggregate(List<RankedBallot> ballots, VotingMethod method) {
        if (ballots == null || ballots.isEmpty()) {
            throw new IllegalArgumentException("Cannot aggregate empty ballot list");
        }

        // Collect the canonical candidate set from the first ballot
        List<String> candidates = new ArrayList<>(ballots.get(0).ranking());

        return switch (method) {
            case PLURALITY      -> plurality(ballots, candidates);
            case BORDA          -> borda(ballots, candidates);
            case SCHULZE        -> schulze(ballots, candidates);
            case APPROVAL       -> approval(ballots, candidates);
        };
    }

    /**
     * Aggregates using the configured default method.
     */
    public VotingResult aggregate(List<RankedBallot> ballots) {
        VotingMethod method;
        try {
            method = VotingMethod.valueOf(defaultMethod.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            method = VotingMethod.SCHULZE;
        }
        return aggregate(ballots, method);
    }

    // ── Plurality ──────────────────────────────────────────────────────────────

    private VotingResult plurality(List<RankedBallot> ballots, List<String> candidates) {
        Map<String, Double> scores = new LinkedHashMap<>();
        candidates.forEach(c -> scores.put(c, 0.0));

        for (RankedBallot b : ballots) {
            if (!b.ranking().isEmpty()) {
                String top = b.ranking().get(0);
                scores.merge(top, 1.0, Double::sum);
            }
        }

        List<String> ranking = rankByScore(scores);
        String winner = ranking.get(0);
        Optional<String> condorcet = findCondorcetWinner(ballots, candidates);
        boolean iia = condorcet.map(w -> w.equals(winner)).orElse(true);

        log.debug("Plurality winner: {} (score={})", winner, scores.get(winner));
        return new VotingResult(winner, ranking, scores, VotingMethod.PLURALITY, condorcet, !iia);
    }

    // ── Borda Count ────────────────────────────────────────────────────────────

    private VotingResult borda(List<RankedBallot> ballots, List<String> candidates) {
        int c = candidates.size();
        Map<String, Double> scores = new LinkedHashMap<>();
        candidates.forEach(cand -> scores.put(cand, 0.0));

        for (RankedBallot b : ballots) {
            for (int i = 0; i < b.ranking().size(); i++) {
                String cand = b.ranking().get(i);
                double points = c - 1 - i;   // first gets c-1, last gets 0
                scores.merge(cand, points, Double::sum);
            }
        }

        List<String> ranking = rankByScore(scores);
        String winner = ranking.get(0);
        Optional<String> condorcet = findCondorcetWinner(ballots, candidates);
        // IIA violated if Borda winner ≠ Condorcet winner (when one exists)
        boolean iiaViolation = condorcet.map(w -> !w.equals(winner)).orElse(false);

        log.debug("Borda winner: {} (score={})", winner, scores.get(winner));
        return new VotingResult(winner, ranking, scores, VotingMethod.BORDA, condorcet, iiaViolation);
    }

    // ── Schulze (Beatpath / Condorcet) ─────────────────────────────────────────

    private VotingResult schulze(List<RankedBallot> ballots, List<String> candidates) {
        int n = candidates.size();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(candidates.get(i), i);

        // d[i][j] = number of voters who prefer candidate i over candidate j
        int[][] d = new int[n][n];
        for (RankedBallot b : ballots) {
            List<String> r = b.ranking();
            for (int i = 0; i < r.size(); i++) {
                for (int j = i + 1; j < r.size(); j++) {
                    int pi = idx.getOrDefault(r.get(i), -1);
                    int pj = idx.getOrDefault(r.get(j), -1);
                    if (pi >= 0 && pj >= 0) d[pi][pj]++;
                }
            }
        }

        // p[i][j] = strength of the strongest beatpath from i to j (Floyd-Warshall)
        int[][] p = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) p[i][j] = d[i][j] > d[j][i] ? d[i][j] : 0;
            }
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i != j && i != k && j != k) {
                        p[i][j] = Math.max(p[i][j], Math.min(p[i][k], p[k][j]));
                    }
                }
            }
        }

        // Schulze winner: candidate i beats j if p[i][j] > p[j][i]
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int wins = 0;
            for (int j = 0; j < n; j++) {
                if (i != j && p[i][j] > p[j][i]) wins++;
            }
            scores.put(candidates.get(i), (double) wins);
        }

        List<String> ranking = rankByScore(scores);
        String winner = ranking.get(0);
        Optional<String> condorcet = findCondorcetWinner(ballots, candidates);

        log.debug("Schulze winner: {} (wins={})", winner, scores.get(winner).intValue());
        return new VotingResult(winner, ranking, scores, VotingMethod.SCHULZE, condorcet, false);
    }

    // ── Approval Voting ────────────────────────────────────────────────────────

    private VotingResult approval(List<RankedBallot> ballots, List<String> candidates) {
        Map<String, Double> scores = new LinkedHashMap<>();
        candidates.forEach(c -> scores.put(c, 0.0));

        for (RankedBallot b : ballots) {
            int approveUpTo = Math.max(1, (int) Math.ceil(b.ranking().size() * APPROVAL_THRESHOLD));
            for (int i = 0; i < Math.min(approveUpTo, b.ranking().size()); i++) {
                scores.merge(b.ranking().get(i), 1.0, Double::sum);
            }
        }

        List<String> ranking = rankByScore(scores);
        String winner = ranking.get(0);
        Optional<String> condorcet = findCondorcetWinner(ballots, candidates);
        boolean iiaViolation = condorcet.map(w -> !w.equals(winner)).orElse(false);

        log.debug("Approval winner: {} (approvals={})", winner, scores.get(winner).intValue());
        return new VotingResult(winner, ranking, scores, VotingMethod.APPROVAL, condorcet, iiaViolation);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private List<String> rankByScore(Map<String, Double> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns the Condorcet winner — the candidate who beats every other candidate
     * in pairwise majority comparisons — or empty if none exists.
     */
    private Optional<String> findCondorcetWinner(List<RankedBallot> ballots, List<String> candidates) {
        for (String a : candidates) {
            boolean beatsAll = true;
            for (String b : candidates) {
                if (a.equals(b)) continue;
                int prefA = 0, prefB = 0;
                for (RankedBallot ballot : ballots) {
                    int ia = ballot.ranking().indexOf(a);
                    int ib = ballot.ranking().indexOf(b);
                    if (ia < ib) prefA++;
                    else if (ib < ia) prefB++;
                }
                if (prefA <= prefB) { beatsAll = false; break; }
            }
            if (beatsAll) return Optional.of(a);
        }
        return Optional.empty();
    }

    // ── DTOs ───────────────────────────────────────────────────────────────────

    /**
     * A ranked ballot submitted by a council member.
     *
     * @param voterId the identifier of the voter (e.g. council member type)
     * @param ranking ordered list of candidates, most preferred first
     */
    public record RankedBallot(String voterId, List<String> ranking) {}

    /** Supported voting methods. */
    public enum VotingMethod {
        /** First-past-the-post: only top preference counts. */
        PLURALITY,
        /** Borda count: positional scoring (n-1 down to 0). */
        BORDA,
        /** Schulze beatpath method: Condorcet-consistent. */
        SCHULZE,
        /** Approval voting: each voter approves top-50% candidates. */
        APPROVAL
    }

    /**
     * Aggregated voting result.
     *
     * @param winner               the elected candidate
     * @param ranking              full ranking from most to least preferred
     * @param scores               raw scores per candidate under the chosen method
     * @param methodUsed           voting method applied
     * @param condorcetWinner      the Condorcet winner if one exists, else empty
     * @param iiaViolationDetected whether the winner differs from the Condorcet winner
     *                             (IIA-related inconsistency; always false for Schulze)
     */
    public record VotingResult(
            String winner,
            List<String> ranking,
            Map<String, Double> scores,
            VotingMethod methodUsed,
            Optional<String> condorcetWinner,
            boolean iiaViolationDetected
    ) {}
}
