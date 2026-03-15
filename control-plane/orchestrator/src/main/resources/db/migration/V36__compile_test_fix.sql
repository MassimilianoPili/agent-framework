-- #186 — Compile-Test-Fix Verification Loop
-- Tracks each iteration of the compile → test → feedback → fix loop.

CREATE TABLE compile_test_iterations (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES execution_sessions(id),
    iteration       INT NOT NULL,
    compilation_ok  BOOLEAN NOT NULL,
    tests_passed    INT NOT NULL DEFAULT 0,
    tests_failed    INT NOT NULL DEFAULT 0,
    feedback_sent   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_compile_test_iter_session ON compile_test_iterations(session_id);
