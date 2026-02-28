-- V8: Token budget tracking
--
-- budget_json:     JSON blob on plans — {onExceeded, perWorkerType: {type -> maxTokens}}
-- plan_token_usage: running token tally per plan/worker-type, updated atomically

ALTER TABLE plans
    ADD COLUMN budget_json TEXT;

CREATE TABLE plan_token_usage (
    id           UUID        PRIMARY KEY,
    plan_id      UUID        NOT NULL REFERENCES plans(id),
    worker_type  VARCHAR(50) NOT NULL,
    tokens_used  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_plan_token_usage UNIQUE (plan_id, worker_type)
);

CREATE INDEX idx_plan_token_usage_plan ON plan_token_usage(plan_id);
