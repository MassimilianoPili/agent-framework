CREATE TABLE plans (
    id              UUID        PRIMARY KEY,
    spec            TEXT        NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    failure_reason  TEXT
);

CREATE INDEX idx_plans_status ON plans(status);
