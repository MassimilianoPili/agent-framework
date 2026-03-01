-- ============================================================================
-- V3: Event Sourcing + Piani Gerarchici
-- Log append-only di tutti gli eventi del piano (audit trail, SSE late-join).
-- Supporto per piani gerarchici via WorkerType.SUB_PLAN.
-- ============================================================================

-- ── 1. Plan Event (hybrid event sourcing) ───────────────────────────────────

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

COMMENT ON TABLE plan_event IS 'Log append-only di tutti gli eventi del piano. Audit trail permanente, niente cascade delete';
COMMENT ON COLUMN plan_event.item_id IS 'Task correlato (NULL = evento a livello piano, non task-specific)';
COMMENT ON COLUMN plan_event.event_type IS 'Tipo evento: PLAN_STARTED, TASK_DISPATCHED, TASK_COMPLETED, TASK_FAILED, PLAN_COMPLETED, PLAN_PAUSED';
COMMENT ON COLUMN plan_event.payload IS 'JSON completo dell''evento (include resultJson per TASK_COMPLETED)';
COMMENT ON COLUMN plan_event.sequence_number IS 'Numero monotonicamente crescente per piano. Usato per SSE late-join replay';

-- ── 2. Piani gerarchici (SUB_PLAN) ─────────────────────────────────────────

ALTER TABLE plan_items
    ADD COLUMN child_plan_id     UUID    REFERENCES plans(id),
    ADD COLUMN await_completion  BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN sub_plan_spec     TEXT;

COMMENT ON COLUMN plan_items.child_plan_id IS 'UUID del piano figlio creato da SUB_PLAN dispatch. NULL per task normali';
COMMENT ON COLUMN plan_items.await_completion IS 'true = il padre attende il completamento del figlio (sequenziale), false = fire-and-forget (parallelo)';
COMMENT ON COLUMN plan_items.sub_plan_spec IS 'Specifica del piano figlio (input per createAndStart). Solo per worker_type = SUB_PLAN';

ALTER TABLE plans
    ADD COLUMN depth          INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN parent_plan_id UUID    REFERENCES plans(id);

COMMENT ON COLUMN plans.depth IS 'Profondita'' nella gerarchia (0 = root). Limitata da PlanRequest.maxDepth (default: 3)';
COMMENT ON COLUMN plans.parent_plan_id IS 'Piano padre nella gerarchia. NULL per piani root';

CREATE INDEX idx_plan_items_child_plan ON plan_items(child_plan_id)
    WHERE child_plan_id IS NOT NULL;
CREATE INDEX idx_plans_parent ON plans(parent_plan_id)
    WHERE parent_plan_id IS NOT NULL;
