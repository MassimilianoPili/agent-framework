CREATE TABLE plan_items (
    id              UUID        PRIMARY KEY,
    plan_id         UUID        NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    ordinal         INTEGER     NOT NULL,
    task_key        VARCHAR(20) NOT NULL,
    title           VARCHAR(500) NOT NULL,
    description     TEXT,
    worker_type     VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    result          TEXT,
    dispatched_at   TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    failure_reason  TEXT,
    UNIQUE (plan_id, task_key)
);

CREATE TABLE plan_item_deps (
    item_id         UUID        NOT NULL REFERENCES plan_items(id) ON DELETE CASCADE,
    depends_on_key  VARCHAR(20) NOT NULL,
    PRIMARY KEY (item_id, depends_on_key)
);

CREATE INDEX idx_plan_items_plan_status ON plan_items(plan_id, status);
