-- #46 — Verifiable Council Deliberation (commit-reveal scheme)
-- Each council member's output is committed with a nonce-based SHA-256 hash.
-- Verification before synthesis ensures no member output was tampered with.

CREATE TABLE council_commitments (
    id                  UUID PRIMARY KEY,
    plan_id             UUID NOT NULL REFERENCES plans(id),
    session_type        VARCHAR(20) NOT NULL,
    task_key            VARCHAR(20),
    member_profile      VARCHAR(100) NOT NULL,
    commit_hash         VARCHAR(64) NOT NULL,
    nonce               UUID NOT NULL,
    raw_output          TEXT NOT NULL,
    committed_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified            BOOLEAN NOT NULL DEFAULT FALSE,
    verification_failed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_council_commitments_plan ON council_commitments(plan_id);
