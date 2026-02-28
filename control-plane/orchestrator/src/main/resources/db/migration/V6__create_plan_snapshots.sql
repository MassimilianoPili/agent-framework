-- Memento Pattern: plan checkpoint/restore for crash recovery and debugging.
CREATE TABLE plan_snapshots (
    id          UUID PRIMARY KEY,
    plan_id     UUID NOT NULL REFERENCES plans(id),
    label       VARCHAR(100) NOT NULL,
    plan_data   TEXT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_plan_snapshots_plan_id ON plan_snapshots(plan_id);
