-- Command Pattern: dispatch attempt history for retry and audit trail.
CREATE TABLE dispatch_attempts (
    id              UUID PRIMARY KEY,
    item_id         UUID NOT NULL REFERENCES plan_items(id),
    attempt_number  INTEGER NOT NULL,
    dispatched_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    success         BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason  TEXT,
    duration_ms     BIGINT,
    UNIQUE (item_id, attempt_number)
);

CREATE INDEX idx_dispatch_attempts_item_id ON dispatch_attempts(item_id);
