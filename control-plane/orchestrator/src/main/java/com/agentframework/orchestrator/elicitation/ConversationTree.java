package com.agentframework.orchestrator.elicitation;

import com.agentframework.orchestrator.elicitation.QuestionRanker.RankedQuestion;
import com.agentframework.orchestrator.elicitation.RequirementsModel.AmbiguityReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the elicitation conversation state with broad-to-specific funneling.
 *
 * <p>Implements the Pohl (2010) three-phase elicitation process adapted for
 * autonomous agents:</p>
 * <ol>
 *   <li><b>Broad phase</b>: high-level architectural questions (max configurable)</li>
 *   <li><b>Specific phase</b>: detailed functional questions (max configurable)</li>
 *   <li><b>Autonomous phase</b>: remaining ambiguities resolved with assumptions</li>
 * </ol>
 *
 * <p>The conversation tree tracks asked questions, received answers, and residual
 * ambiguities. It decides whether to ask or assume based on EVPI threshold
 * from {@link QuestionRanker}.</p>
 *
 * <p>State is serializable to JSONB for persistence in {@code elicitation_sessions.conversation_state}.</p>
 *
 * @see <a href="https://doi.org/10.1007/978-3-642-12578-2">Pohl, K. (2010). Requirements Engineering</a>
 */
public class ConversationTree {

    private static final Logger log = LoggerFactory.getLogger(ConversationTree.class);

    /** Current phase of the conversation. */
    public enum Phase { BROAD, SPECIFIC, AUTONOMOUS, COMPLETED }

    private Phase currentPhase = Phase.BROAD;
    private int broadQuestionsAsked = 0;
    private int specificQuestionsAsked = 0;
    private int assumptionsMade = 0;
    private int totalRounds = 0;
    private final List<Turn> turns = new ArrayList<>();

    private final int broadMax;
    private final int specificMax;
    private final int maxRounds;
    private final double evpiThreshold;
    private final double assumptionConfidenceMin;

    public ConversationTree(ElicitationConfig config) {
        this.broadMax = config.funneling().broadPhaseMaxQuestions();
        this.specificMax = config.funneling().specificPhaseMaxQuestions();
        this.maxRounds = config.questions().maxRounds();
        this.evpiThreshold = config.questions().evpiThreshold();
        this.assumptionConfidenceMin = config.autonomy().assumptionConfidenceMin();
    }

    /**
     * Determines next action based on current state and ranked questions.
     *
     * @param rankedQuestions questions ranked by EVPI
     * @return next action: ASK (with questions), ASSUME, or COMPLETE
     */
    public NextAction decide(List<RankedQuestion> rankedQuestions) {
        // Check termination conditions
        if (currentPhase == Phase.COMPLETED) {
            return new NextAction(Action.COMPLETE, List.of(), null);
        }
        if (totalRounds >= maxRounds) {
            currentPhase = Phase.AUTONOMOUS;
        }

        // No questions or EVPI below threshold → autonomous mode
        if (rankedQuestions.isEmpty() || rankedQuestions.getFirst().evpi() < evpiThreshold) {
            currentPhase = Phase.AUTONOMOUS;
            return new NextAction(Action.ASSUME, List.of(), "EVPI below threshold, proceeding with assumptions");
        }

        // Select questions for current phase
        int allowedQuestions = switch (currentPhase) {
            case BROAD -> broadMax - broadQuestionsAsked;
            case SPECIFIC -> specificMax - specificQuestionsAsked;
            default -> 0;
        };

        if (allowedQuestions <= 0) {
            advancePhase();
            return decide(rankedQuestions); // recurse once with new phase
        }

        List<RankedQuestion> selected = rankedQuestions.subList(0,
                Math.min(rankedQuestions.size(), allowedQuestions));

        return new NextAction(Action.ASK, selected, currentPhase.name() + " phase");
    }

    /**
     * Records a question that was asked.
     *
     * @param question  the question
     * @param evpi      the EVPI score
     */
    public void recordQuestion(String question, double evpi) {
        turns.add(new Turn(turns.size(), question, null, evpi, false, 0.0));
        switch (currentPhase) {
            case BROAD -> broadQuestionsAsked++;
            case SPECIFIC -> specificQuestionsAsked++;
            default -> {}
        }
    }

    /**
     * Records an answer to a previously asked question.
     *
     * @param turnIndex the turn to update
     * @param answer    the user's answer
     */
    public void recordAnswer(int turnIndex, String answer) {
        if (turnIndex >= 0 && turnIndex < turns.size()) {
            Turn old = turns.get(turnIndex);
            turns.set(turnIndex, new Turn(old.index(), old.question(), answer,
                    old.evpi(), false, old.confidence()));
        }
        totalRounds++;
    }

    /**
     * Records an autonomous assumption.
     *
     * @param assumedText what was assumed
     * @param confidence  confidence in the assumption [0,1]
     */
    public void recordAssumption(String assumedText, double confidence) {
        turns.add(new Turn(turns.size(), null, assumedText, 0.0, true, confidence));
        assumptionsMade++;
    }

    /**
     * Marks the conversation as completed.
     */
    public void complete() {
        currentPhase = Phase.COMPLETED;
    }

    // --- Accessors ---

    public Phase currentPhase() { return currentPhase; }
    public int questionsAsked() { return broadQuestionsAsked + specificQuestionsAsked; }
    public int assumptionsMade() { return assumptionsMade; }
    public int totalRounds() { return totalRounds; }
    public List<Turn> turns() { return List.copyOf(turns); }

    /**
     * Returns unanswered questions (asked but no answer yet).
     */
    public List<Turn> pendingQuestions() {
        return turns.stream()
                .filter(t -> !t.isAssumption() && t.question() != null && t.answer() == null)
                .toList();
    }

    private void advancePhase() {
        currentPhase = switch (currentPhase) {
            case BROAD -> Phase.SPECIFIC;
            case SPECIFIC -> Phase.AUTONOMOUS;
            case AUTONOMOUS -> Phase.COMPLETED;
            case COMPLETED -> Phase.COMPLETED;
        };
        log.debug("Conversation advanced to phase: {}", currentPhase);
    }

    // --- Types ---

    public enum Action { ASK, ASSUME, COMPLETE }

    public record NextAction(
            Action action,
            List<RankedQuestion> questions,
            @Nullable String reason
    ) {}

    public record Turn(
            int index,
            @Nullable String question,
            @Nullable String answer,
            double evpi,
            boolean isAssumption,
            double confidence
    ) {}
}
