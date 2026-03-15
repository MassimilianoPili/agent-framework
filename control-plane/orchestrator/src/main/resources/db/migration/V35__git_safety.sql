-- #185 — Git Safety Protocol Enforcer
-- Audit log for git command evaluation: risk classification, sequence analysis, verdicts.

CREATE TABLE git_command_log (
    id          UUID PRIMARY KEY,
    session_id  VARCHAR(100) NOT NULL,
    raw_command TEXT NOT NULL,
    risk_level  VARCHAR(20) NOT NULL,
    verdict     VARCHAR(20) NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_git_command_log_session ON git_command_log(session_id);
CREATE INDEX idx_git_command_log_verdict ON git_command_log(verdict);
CREATE INDEX idx_git_command_log_created ON git_command_log(created_at);
