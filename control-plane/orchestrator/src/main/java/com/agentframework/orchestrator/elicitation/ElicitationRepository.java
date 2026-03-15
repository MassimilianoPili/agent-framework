package com.agentframework.orchestrator.elicitation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Repository for elicitation sessions and turns.
 *
 * <p>Uses JdbcTemplate directly for JSONB handling and to match
 * the project's convention for non-entity persistence.</p>
 */
@Repository
@ConditionalOnProperty(prefix = "elicitation", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ElicitationRepository {

    private final JdbcTemplate jdbc;

    public ElicitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates a new elicitation session.
     */
    public void saveSession(UUID sessionId, String originalSpec, String status,
                             int questionsAsked, int assumptionsMade) {
        jdbc.update("""
            INSERT INTO elicitation_sessions (id, original_spec, status, questions_asked,
                assumptions_made, created_at, updated_at)
            VALUES (?::uuid, ?, ?, ?, ?, NOW(), NOW())
            """, sessionId.toString(), originalSpec, status, questionsAsked, assumptionsMade);
    }

    /**
     * Updates session status and counters.
     */
    public void updateSession(UUID sessionId, String status,
                                int questionsAsked, int assumptionsMade) {
        jdbc.update("""
            UPDATE elicitation_sessions
            SET status = ?, questions_asked = ?, assumptions_made = ?, updated_at = NOW()
            WHERE id = ?::uuid
            """, status, questionsAsked, assumptionsMade, sessionId.toString());
    }

    /**
     * Links an elicitation session to a plan.
     */
    public void linkToPlan(UUID sessionId, UUID planId) {
        jdbc.update("UPDATE elicitation_sessions SET plan_id = ?::uuid WHERE id = ?::uuid",
                planId.toString(), sessionId.toString());
    }

    /**
     * Updates structured requirements JSONB.
     */
    public void updateRequirements(UUID sessionId, String requirementsJson) {
        jdbc.update("""
            UPDATE elicitation_sessions SET structured_requirements = ?::jsonb, updated_at = NOW()
            WHERE id = ?::uuid
            """, requirementsJson, sessionId.toString());
    }

    /**
     * Updates ambiguity report JSONB.
     */
    public void updateAmbiguityReport(UUID sessionId, String reportJson) {
        jdbc.update("""
            UPDATE elicitation_sessions SET ambiguity_report = ?::jsonb, updated_at = NOW()
            WHERE id = ?::uuid
            """, reportJson, sessionId.toString());
    }

    /**
     * Saves a conversation turn (question or assumption).
     */
    public void saveTurn(UUID sessionId, int turnNumber, @Nullable String question,
                          @Nullable String answer, double informationGain,
                          boolean isAssumption, double assumptionConfidence) {
        jdbc.update("""
            INSERT INTO elicitation_turns (id, session_id, turn_number, question, answer,
                information_gain, is_assumption, assumption_confidence, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, NOW())
            """, UUID.randomUUID().toString(), sessionId.toString(), turnNumber,
                question, answer, informationGain, isAssumption, assumptionConfidence);
    }

    /**
     * Records an answer for a turn.
     */
    public void recordAnswer(UUID sessionId, int turnNumber, String answer) {
        jdbc.update("""
            UPDATE elicitation_turns SET answer = ?
            WHERE session_id = ?::uuid AND turn_number = ?
            """, answer, sessionId.toString(), turnNumber);
    }

    /**
     * Finds active sessions (for monitoring).
     */
    public List<Map<String, Object>> findActiveSessions(int limit) {
        return jdbc.queryForList("""
            SELECT id, status, questions_asked, assumptions_made, created_at
            FROM elicitation_sessions
            WHERE status IN ('ACTIVE', 'WAITING_RESPONSE')
            ORDER BY created_at DESC
            LIMIT ?
            """, limit);
    }

    /**
     * Finds the session for a plan.
     */
    @Nullable
    public Map<String, Object> findByPlanId(UUID planId) {
        List<Map<String, Object>> results = jdbc.queryForList("""
            SELECT id, original_spec, structured_requirements, ambiguity_report,
                status, questions_asked, assumptions_made
            FROM elicitation_sessions
            WHERE plan_id = ?::uuid
            """, planId.toString());
        return results.isEmpty() ? null : results.getFirst();
    }
}
