package com.agentframework.orchestrator.elicitation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Conversational Requirements Elicitor.
 *
 * <pre>
 * elicitation:
 *   enabled: false
 *   ambiguity:
 *     uncertainty-threshold: 0.6
 *     max-segments: 20
 *   questions:
 *     max-per-round: 3
 *     evpi-threshold: 0.3
 *     max-rounds: 5
 *   autonomy:
 *     assumption-confidence-min: 0.7
 *     assumed-prefix: "[ASSUMED]"
 *   funneling:
 *     broad-phase-max-questions: 5
 *     specific-phase-max-questions: 10
 * </pre>
 *
 * @param ambiguity  LLM uncertainty scoring parameters
 * @param questions  question selection and ranking
 * @param autonomy   autonomous assumption thresholds
 * @param funneling  broad-to-specific conversation structure (Pohl 2010)
 */
@ConfigurationProperties(prefix = "elicitation")
public record ElicitationConfig(
        AmbiguityConfig ambiguity,
        QuestionConfig questions,
        AutonomyConfig autonomy,
        FunnelingConfig funneling
) {
    public ElicitationConfig {
        if (ambiguity == null) ambiguity = new AmbiguityConfig(0.6, 20);
        if (questions == null) questions = new QuestionConfig(3, 0.3, 5);
        if (autonomy == null) autonomy = new AutonomyConfig(0.7, "[ASSUMED]");
        if (funneling == null) funneling = new FunnelingConfig(5, 10);
    }

    /**
     * @param uncertaintyThreshold segments above this score are considered ambiguous
     * @param maxSegments          max spec segments to analyze
     */
    public record AmbiguityConfig(double uncertaintyThreshold, int maxSegments) {
        public AmbiguityConfig {
            if (uncertaintyThreshold <= 0 || uncertaintyThreshold >= 1.0) uncertaintyThreshold = 0.6;
            if (maxSegments <= 0) maxSegments = 20;
        }
    }

    /**
     * @param maxPerRound   max questions per elicitation round
     * @param evpiThreshold below this EVPI, stop asking and assume
     * @param maxRounds     max conversation rounds before forcing autonomous mode
     */
    public record QuestionConfig(int maxPerRound, double evpiThreshold, int maxRounds) {
        public QuestionConfig {
            if (maxPerRound <= 0) maxPerRound = 3;
            if (evpiThreshold <= 0 || evpiThreshold >= 1.0) evpiThreshold = 0.3;
            if (maxRounds <= 0) maxRounds = 5;
        }
    }

    /**
     * @param assumptionConfidenceMin min confidence to make autonomous assumption
     * @param assumedPrefix           prefix tag for assumed requirements
     */
    public record AutonomyConfig(double assumptionConfidenceMin, String assumedPrefix) {
        public AutonomyConfig {
            if (assumptionConfidenceMin <= 0 || assumptionConfidenceMin >= 1.0) assumptionConfidenceMin = 0.7;
            if (assumedPrefix == null || assumedPrefix.isBlank()) assumedPrefix = "[ASSUMED]";
        }
    }

    /**
     * @param broadPhaseMaxQuestions    max high-level architectural questions
     * @param specificPhaseMaxQuestions  max detailed functional questions
     */
    public record FunnelingConfig(int broadPhaseMaxQuestions, int specificPhaseMaxQuestions) {
        public FunnelingConfig {
            if (broadPhaseMaxQuestions <= 0) broadPhaseMaxQuestions = 5;
            if (specificPhaseMaxQuestions <= 0) specificPhaseMaxQuestions = 10;
        }
    }
}
