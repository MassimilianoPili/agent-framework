-- #179 — Conversational Requirements Elicitor
-- Tracks elicitation sessions (ambiguity analysis, conversation state, structured requirements)
-- and individual Q&A turns with EVPI scores and assumption tracking.

CREATE TABLE elicitation_sessions (
    id                       UUID PRIMARY KEY,
    plan_id                  UUID REFERENCES plans(id),
    original_spec            TEXT NOT NULL,
    structured_requirements  JSONB,
    ambiguity_report         JSONB,
    conversation_state       JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    questions_asked          INT NOT NULL DEFAULT 0,
    assumptions_made         INT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_elicitation_sessions_plan ON elicitation_sessions(plan_id);
CREATE INDEX idx_elicitation_sessions_status ON elicitation_sessions(status);

CREATE TABLE elicitation_turns (
    id                    UUID PRIMARY KEY,
    session_id            UUID NOT NULL REFERENCES elicitation_sessions(id),
    turn_number           INT NOT NULL,
    question              TEXT,
    answer                TEXT,
    information_gain      DOUBLE PRECISION,
    is_assumption         BOOLEAN NOT NULL DEFAULT FALSE,
    assumption_confidence DOUBLE PRECISION,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_elicitation_turns_session ON elicitation_turns(session_id, turn_number);
