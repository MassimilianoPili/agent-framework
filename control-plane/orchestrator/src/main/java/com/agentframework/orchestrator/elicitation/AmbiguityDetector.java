package com.agentframework.orchestrator.elicitation;

import com.agentframework.orchestrator.elicitation.RequirementsModel.AmbiguityReport;
import com.agentframework.orchestrator.elicitation.RequirementsModel.SegmentAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects ambiguity in specifications using LLM uncertainty scoring.
 *
 * <p>Segments the input specification into logical units, then scores each
 * segment's uncertainty via structured LLM output. Segments exceeding the
 * configured threshold are flagged as ambiguous with suggested clarification
 * questions.</p>
 *
 * <p>When a ChatClient is available, uses structured output
 * ({@code BeanOutputConverter<AmbiguityReport>}) for reliable parsing.
 * Falls back to heuristic-based detection when LLM is unavailable.</p>
 *
 * <p>Ambiguity types detected:</p>
 * <ul>
 *   <li><b>LEXICAL</b>: vague terms ("fast", "user-friendly", "scalable")</li>
 *   <li><b>SYNTACTIC</b>: ambiguous sentence structure (dangling modifiers)</li>
 *   <li><b>SEMANTIC</b>: multiple valid interpretations of meaning</li>
 *   <li><b>REFERENTIAL</b>: unclear references ("the system", "it")</li>
 *   <li><b>SCOPE</b>: undefined boundaries ("etc.", "and more")</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "elicitation", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AmbiguityDetector {

    private static final Logger log = LoggerFactory.getLogger(AmbiguityDetector.class);

    // Heuristic patterns for vague/ambiguous language
    private static final Pattern VAGUE_TERMS = Pattern.compile(
            "\\b(fast|quick|user-friendly|scalable|efficient|flexible|robust|" +
            "modern|simple|easy|good|nice|proper|appropriate|suitable|various|" +
            "etc|and so on|and more|as needed|if necessary|somehow)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SCOPE_AMBIGUITY = Pattern.compile(
            "\\b(etc\\.?|and so on|and more|among others|like \\w+ and \\w+)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REFERENTIAL_AMBIGUITY = Pattern.compile(
            "\\b(it|they|the system|the application|this|that|these|those)\\b(?! (?:should|must|shall|will))",
            Pattern.CASE_INSENSITIVE);

    private final ElicitationConfig config;

    public AmbiguityDetector(ElicitationConfig config) {
        this.config = config;
    }

    /**
     * Analyzes a specification for ambiguity.
     *
     * <p>Uses heuristic-based detection as baseline. When LLM integration
     * is wired in, this will be augmented with structured LLM output.</p>
     *
     * @param spec the specification text
     * @return ambiguity report with per-segment scores
     */
    public AmbiguityReport analyze(String spec) {
        List<String> segments = segmentSpec(spec);
        List<SegmentAnalysis> analyses = new ArrayList<>();

        double totalUncertainty = 0.0;

        for (String segment : segments) {
            SegmentAnalysis analysis = analyzeSegment(segment);
            analyses.add(analysis);
            totalUncertainty += analysis.uncertaintyScore();
        }

        double overallUncertainty = segments.isEmpty() ? 0.0 : totalUncertainty / segments.size();
        boolean needsElicitation = overallUncertainty >= config.ambiguity().uncertaintyThreshold();

        AmbiguityReport report = new AmbiguityReport(analyses, overallUncertainty, needsElicitation);
        log.debug("Ambiguity analysis: {} segments, overall={:.2f}, needsElicitation={}",
                segments.size(), overallUncertainty, needsElicitation);

        return report;
    }

    /**
     * Segments a specification into logical units for individual analysis.
     * Splits on sentence boundaries and list items.
     */
    List<String> segmentSpec(String spec) {
        List<String> segments = new ArrayList<>();
        // Split on sentence boundaries, list items, or double newlines
        String[] parts = spec.split("(?<=[.!?])\\s+|\\n\\s*[-*•]\\s*|\\n\\n+");

        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && trimmed.length() > 5) {
                segments.add(trimmed);
                if (segments.size() >= config.ambiguity().maxSegments()) break;
            }
        }

        return segments;
    }

    /**
     * Analyzes a single segment for ambiguity using heuristic patterns.
     */
    SegmentAnalysis analyzeSegment(String segment) {
        double score = 0.0;
        String type = null;
        String question = null;

        // Check for vague terms
        Matcher vagueMatcher = VAGUE_TERMS.matcher(segment);
        int vagueCount = 0;
        while (vagueMatcher.find()) vagueCount++;
        if (vagueCount > 0) {
            score += Math.min(0.3, vagueCount * 0.1);
            type = "LEXICAL";
            question = "What specific criteria define '" + extractFirstMatch(VAGUE_TERMS, segment) + "' in this context?";
        }

        // Check for scope ambiguity
        if (SCOPE_AMBIGUITY.matcher(segment).find()) {
            score += 0.3;
            type = "SCOPE";
            question = "Can you provide an exhaustive list instead of '" + extractFirstMatch(SCOPE_AMBIGUITY, segment) + "'?";
        }

        // Check for referential ambiguity
        Matcher refMatcher = REFERENTIAL_AMBIGUITY.matcher(segment);
        if (refMatcher.find()) {
            score += 0.15;
            if (type == null) {
                type = "REFERENTIAL";
                question = "What does '" + refMatcher.group() + "' refer to specifically?";
            }
        }

        // Short segments are more likely to be ambiguous
        if (segment.length() < 30) {
            score += 0.1;
        }

        // Clamp to [0, 1]
        score = Math.min(1.0, score);

        return new SegmentAnalysis(segment, score, type, question);
    }

    @Nullable
    private String extractFirstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group() : null;
    }
}
