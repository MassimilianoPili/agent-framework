-- G5: Persistent audit event storage.
-- Replaces the in-memory CopyOnWriteArrayList in AuditManagerService.
-- Events older than 30 days are purged by a nightly scheduled job.

CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    task_key    VARCHAR(255),
    tool        VARCHAR(255),
    worker      VARCHAR(100),
    session     VARCHAR(255),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw         JSONB
);

CREATE INDEX idx_audit_task_key    ON audit_events (task_key);
CREATE INDEX idx_audit_occurred_at ON audit_events (occurred_at);
