-- #33 Token Economics: double-entry ledger for per-plan token accounting
-- Append-only table tracking token debits (consumption) and credits (value production).
-- Accounting invariant: SUM(debit) - SUM(credit) = net_spend (verifiable via SQL).

CREATE TABLE token_ledger (
    id            UUID PRIMARY KEY,
    plan_id       UUID NOT NULL,
    item_id       UUID,
    task_key      VARCHAR(20),
    worker_type   VARCHAR(50) NOT NULL,
    entry_type    VARCHAR(6)  NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount        BIGINT      NOT NULL CHECK (amount >= 0),
    balance_after BIGINT      NOT NULL,
    description   VARCHAR(200),
    created_at    TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_token_ledger_plan_id ON token_ledger(plan_id);
CREATE INDEX idx_token_ledger_plan_created ON token_ledger(plan_id, created_at);
