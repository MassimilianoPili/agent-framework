-- V18: compensation_mode — records the last compensation applied to a plan.
-- Enables auditing and prevents ambiguous re-opens (UNDO vs RETRY vs AMENDMENT).

ALTER TABLE plans
    ADD COLUMN compensation_mode VARCHAR(20);

COMMENT ON COLUMN plans.compensation_mode IS
    'Last compensation applied to this plan: UNDO (saga rollback), RETRY (reset FAILED items), AMENDMENT (add new tasks)';
