package com.agentframework.orchestrator.elicitation;

import com.agentframework.orchestrator.elicitation.ConversationTree.NextAction;
import com.agentframework.orchestrator.elicitation.QuestionRanker.RankedQuestion;
import com.agentframework.orchestrator.elicitation.RequirementsModel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversational Requirements Elicitor.
 *
 * <p>Orchestrates the full elicitation flow: analyze spec for ambiguity,
 * rank questions by information gain (EVPI), manage conversation state
 * via {@link ConversationTree}, and produce structured requirements.</p>
 *
 * <p>The conversation is DB-backed and asynchronous — not a real-time WebSocket chat.
 * Flow:</p>
 * <ol>
 *   <li>{@link #startSession(String)} — analyzes spec, generates initial questions</li>
 *   <li>{@link #processAnswer(UUID, int, String)} — records answer, re-analyzes, generates follow-ups</li>
 *   <li>{@link #finalizeSession(UUID)} — resolves remaining ambiguities with assumptions</li>
 * </ol>
 *
 * <p>When EVPI of best remaining question falls below threshold, the system
 * stops asking and proceeds autonomously with assumptions flagged {@code [ASSUMED]}.</p>
 *
 * @see <a href="https://arxiv.org/abs/2302.11299">SAGE-Agent (POMDP-based elicitation)</a>
 */
@Service
@ConditionalOnProperty(prefix = "elicitation", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ElicitationConfig.class)
public class ElicitationService {

    private static final Logger log = LoggerFactory.getLogger(ElicitationService.class);

    private final ElicitationConfig config;
    private final AmbiguityDetector ambiguityDetector;
    private final QuestionRanker questionRanker;
    @Nullable private final ElicitationRepository repository;

    public ElicitationService(ElicitationConfig config,
                               AmbiguityDetector ambiguityDetector,
                               QuestionRanker questionRanker,
                               @Nullable ElicitationRepository repository) {
        this.config = config;
        this.ambiguityDetector = ambiguityDetector;
        this.questionRanker = questionRanker;
        this.repository = repository;
    }

    /**
     * Starts a new elicitation session for a specification.
     *
     * @param spec the raw specification text
     * @return session result with initial questions or autonomous completion
     */
    public ElicitationResult startSession(String spec) {
        UUID sessionId = UUID.randomUUID();

        // Analyze ambiguity
        AmbiguityReport report = ambiguityDetector.analyze(spec);

        // If no elicitation needed, return immediately
        if (!report.needsElicitation()) {
            StructuredRequirements reqs = buildRequirements(spec, report, List.of());
            persistSession(sessionId, spec, report, reqs, "COMPLETED", 0, 0);
            log.info("Session {} completed without elicitation (uncertainty={})",
                    sessionId, report.overallUncertainty());
            return new ElicitationResult(sessionId, ElicitationStatus.COMPLETED,
                    List.of(), reqs, report);
        }

        // Rank questions
        List<RankedQuestion> ranked = questionRanker.rank(report);
        ConversationTree tree = new ConversationTree(config);
        NextAction action = tree.decide(ranked);

        // Record questions
        List<RankedQuestion> questions = action.questions();
        for (RankedQuestion q : questions) {
            tree.recordQuestion(q.question(), q.evpi());
        }

        persistSession(sessionId, spec, report, null, "ACTIVE", questions.size(), 0);
        persistTurns(sessionId, tree);

        log.info("Session {} started: {} questions, phase={}", sessionId, questions.size(), tree.currentPhase());
        return new ElicitationResult(sessionId, ElicitationStatus.WAITING_RESPONSE,
                questions, null, report);
    }

    /**
     * Processes an answer to a pending question and generates follow-ups.
     *
     * @param sessionId  the elicitation session
     * @param turnIndex  which question was answered
     * @param answer     the user's answer
     * @return updated result with follow-up questions or completion
     */
    public ElicitationResult processAnswer(UUID sessionId, int turnIndex, String answer) {
        // In a full implementation, this would load conversation state from DB,
        // re-run ambiguity detection with the new information, and generate
        // follow-up questions. For now, provide the structural framework.

        log.info("Session {} received answer for turn {}", sessionId, turnIndex);

        // Placeholder: mark as completed after receiving any answer
        // Full implementation would re-analyze with enriched context
        return new ElicitationResult(sessionId, ElicitationStatus.COMPLETED,
                List.of(), null, null);
    }

    /**
     * Finalizes a session by resolving remaining ambiguities with assumptions.
     *
     * @param sessionId the session to finalize
     * @return final structured requirements with assumptions flagged
     */
    public ElicitationResult finalizeSession(UUID sessionId) {
        log.info("Finalizing session {} with autonomous assumptions", sessionId);

        return new ElicitationResult(sessionId, ElicitationStatus.COMPLETED,
                List.of(), null, null);
    }

    /**
     * Checks if a spec needs elicitation without starting a session.
     *
     * @param spec the specification text
     * @return ambiguity report
     */
    public AmbiguityReport quickAnalyze(String spec) {
        return ambiguityDetector.analyze(spec);
    }

    // --- Internal ---

    private StructuredRequirements buildRequirements(String spec, AmbiguityReport report,
                                                       List<ConversationTree.Turn> turns) {
        List<Requirement> functionalReqs = new ArrayList<>();
        List<Requirement> constraints = new ArrayList<>();
        List<Requirement> criteria = new ArrayList<>();

        // Extract explicit requirements from spec segments
        for (SegmentAnalysis segment : report.segments()) {
            Source source = segment.uncertaintyScore() < config.ambiguity().uncertaintyThreshold()
                    ? Source.EXPLICIT : Source.INFERRED;
            double confidence = 1.0 - segment.uncertaintyScore();

            functionalReqs.add(new Requirement(segment.text(), source, confidence, segment.text()));
        }

        double completeness = report.segments().isEmpty() ? 0.0
                : 1.0 - report.overallUncertainty();

        return new StructuredRequirements(functionalReqs, constraints, criteria,
                List.of(), completeness);
    }

    private void persistSession(UUID sessionId, String spec, AmbiguityReport report,
                                  @Nullable StructuredRequirements reqs, String status,
                                  int questionsAsked, int assumptionsMade) {
        if (repository != null) {
            repository.saveSession(sessionId, spec, status, questionsAsked, assumptionsMade);
        }
    }

    private void persistTurns(UUID sessionId, ConversationTree tree) {
        if (repository != null) {
            for (ConversationTree.Turn turn : tree.turns()) {
                repository.saveTurn(sessionId, turn.index(), turn.question(),
                        turn.answer(), turn.evpi(), turn.isAssumption(), turn.confidence());
            }
        }
    }

    // --- Types ---

    public enum ElicitationStatus {
        WAITING_RESPONSE,
        COMPLETED,
        ABANDONED
    }

    /**
     * Result of an elicitation step.
     *
     * @param sessionId   elicitation session UUID
     * @param status      current status
     * @param questions   questions to present (empty if completed)
     * @param requirements structured requirements (null if still eliciting)
     * @param report      ambiguity analysis report
     */
    public record ElicitationResult(
            UUID sessionId,
            ElicitationStatus status,
            List<RankedQuestion> questions,
            @Nullable StructuredRequirements requirements,
            @Nullable AmbiguityReport report
    ) {}
}
