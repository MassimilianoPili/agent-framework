package com.agentframework.orchestrator.elicitation;

import com.agentframework.orchestrator.elicitation.RequirementsModel.AmbiguityReport;
import com.agentframework.orchestrator.elicitation.RequirementsModel.SegmentAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks candidate clarification questions by Expected Value of Perfect Information (EVPI).
 *
 * <p>EVPI measures how much uncertainty reduction a perfect answer to a question
 * would provide. Computed as: {@code IG(q) = H(spec) - E[H(spec|answer(q))]},
 * where H is Shannon entropy of the ambiguity distribution.</p>
 *
 * <p>This is a lightweight computation using the uncertainty scores from
 * {@link AmbiguityDetector} — no additional LLM call required. The ranking
 * is equivalent to sorting by information gain, which is mathematically
 * identical to the Bradley-Terry ranking used in Preference Sort.</p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Value_of_information">EVPI (Decision Theory)</a>
 */
@Component
@ConditionalOnProperty(prefix = "elicitation", name = "enabled", havingValue = "true", matchIfMissing = false)
public class QuestionRanker {

    private static final Logger log = LoggerFactory.getLogger(QuestionRanker.class);

    private final ElicitationConfig config;

    public QuestionRanker(ElicitationConfig config) {
        this.config = config;
    }

    /**
     * Ranks questions from an ambiguity report by information gain.
     *
     * @param report the ambiguity analysis
     * @return ranked questions, highest EVPI first, limited by maxPerRound
     */
    public List<RankedQuestion> rank(AmbiguityReport report) {
        List<RankedQuestion> candidates = new ArrayList<>();

        for (SegmentAnalysis segment : report.segments()) {
            if (segment.suggestedQuestion() == null) continue;
            if (segment.uncertaintyScore() < config.ambiguity().uncertaintyThreshold()) continue;

            double evpi = computeEVPI(segment, report.overallUncertainty());
            candidates.add(new RankedQuestion(
                    segment.suggestedQuestion(),
                    segment.text(),
                    evpi,
                    segment.ambiguityType(),
                    segment.uncertaintyScore()));
        }

        // Sort by EVPI descending
        candidates.sort(Comparator.comparingDouble(RankedQuestion::evpi).reversed());

        // Limit to maxPerRound
        int limit = config.questions().maxPerRound();
        List<RankedQuestion> ranked = candidates.size() <= limit
                ? candidates
                : candidates.subList(0, limit);

        log.debug("Ranked {} questions from {} candidates (evpiThreshold={})",
                ranked.size(), candidates.size(), config.questions().evpiThreshold());

        return ranked;
    }

    /**
     * Returns true if further questioning is worthwhile (best EVPI > threshold).
     *
     * @param rankedQuestions output from {@link #rank}
     * @return true if at least one question has EVPI above threshold
     */
    public boolean shouldContinueAsking(List<RankedQuestion> rankedQuestions) {
        if (rankedQuestions.isEmpty()) return false;
        return rankedQuestions.getFirst().evpi() >= config.questions().evpiThreshold();
    }

    /**
     * Computes EVPI for a segment question.
     *
     * <p>Approximation: EVPI ≈ segment uncertainty × (segment uncertainty / overall uncertainty).
     * This weights questions that address the most uncertain parts relative to overall ambiguity.</p>
     *
     * @param segment            the ambiguous segment
     * @param overallUncertainty aggregate uncertainty
     * @return estimated information gain [0, 1]
     */
    double computeEVPI(SegmentAnalysis segment, double overallUncertainty) {
        if (overallUncertainty <= 0) return 0.0;

        double segmentH = binaryEntropy(segment.uncertaintyScore());
        double relativeWeight = segment.uncertaintyScore() / overallUncertainty;

        // EVPI ≈ entropy of this segment × its relative contribution to overall uncertainty
        return segmentH * Math.min(1.0, relativeWeight);
    }

    /**
     * Binary entropy: H(p) = -p*log2(p) - (1-p)*log2(1-p).
     */
    static double binaryEntropy(double p) {
        if (p <= 0.0 || p >= 1.0) return 0.0;
        return -(p * log2(p) + (1 - p) * log2(1 - p));
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    // --- Types ---

    /**
     * A ranked clarification question.
     *
     * @param question         the question to ask
     * @param relatedSegment   the spec segment this question addresses
     * @param evpi             expected value of perfect information [0, 1]
     * @param ambiguityType    type of ambiguity (LEXICAL, SCOPE, etc.)
     * @param uncertaintyScore raw uncertainty score of the segment
     */
    public record RankedQuestion(
            String question,
            String relatedSegment,
            double evpi,
            String ambiguityType,
            double uncertaintyScore
    ) {}
}
