-- Append-only event log for hybrid event sourcing.
-- Retains a full audit trail of all plan state transitions.
-- The existing Plan/PlanItem tables remain the primary read model.

CREATE TABLE plan_event (
    id              UUID         NOT NULL,
    plan_id         UUID         NOT NULL REFERENCES plans(id),
    item_id         UUID         REFERENCES plan_items(id),
    event_type      VARCHAR(64)  NOT NULL,
    payload         TEXT,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    sequence_number BIGINT       NOT NULL,

    CONSTRAINT pk_plan_event PRIMARY KEY (id),
    CONSTRAINT uq_plan_event_seq UNIQUE (plan_id, sequence_number)
);

CREATE INDEX idx_plan_event_plan_seq ON plan_event(plan_id, sequence_number);
