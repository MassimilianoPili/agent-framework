-- #177 — Execution Runtime Orchestrator
-- Tracks sandbox execution sessions and structured error extraction.
-- Enables cost accounting, capacity planning, and compile-test-fix loop feedback.

CREATE TABLE execution_sessions (
    id              UUID PRIMARY KEY,
    plan_id         UUID REFERENCES plans(id),
    item_id         UUID REFERENCES plan_items(id),
    language        VARCHAR(20) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMPTZ,
    exit_code       INT NOT NULL,
    duration_ms     BIGINT NOT NULL DEFAULT 0,
    resource_usage  JSONB
);

CREATE INDEX idx_execution_sessions_plan ON execution_sessions(plan_id);
CREATE INDEX idx_execution_sessions_item ON execution_sessions(item_id);
CREATE INDEX idx_execution_sessions_language ON execution_sessions(language);

CREATE TABLE execution_errors (
    id          UUID PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES execution_sessions(id),
    error_type  VARCHAR(20) NOT NULL,
    file        VARCHAR(500),
    line        INT,
    message     TEXT NOT NULL,
    stack_trace TEXT
);

CREATE INDEX idx_execution_errors_session ON execution_errors(session_id);
