-- ============================================================================
-- V1: Orchestrator Core
-- Tabelle fondamentali per l'orchestrazione dei piani: plans, items,
-- dipendenze, quality gates, tentativi di dispatch, snapshot di recovery.
-- ============================================================================

-- ── 1. Plans ────────────────────────────────────────────────────────────────

CREATE TABLE plans (
    id              UUID        PRIMARY KEY,
    spec            TEXT        NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    failure_reason  TEXT
);

CREATE INDEX idx_plans_status ON plans(status);

COMMENT ON TABLE plans IS 'Piani di orchestrazione: ogni piano e'' un DAG di task da eseguire';
COMMENT ON COLUMN plans.id IS 'UUID univoco del piano';
COMMENT ON COLUMN plans.spec IS 'Specifica testuale del piano (input utente o generata dal planner)';
COMMENT ON COLUMN plans.status IS 'Stato lifecycle: PENDING, IN_PROGRESS, COMPLETED, FAILED, PAUSED';
COMMENT ON COLUMN plans.created_at IS 'Timestamp creazione piano';
COMMENT ON COLUMN plans.completed_at IS 'Timestamp completamento (successo o fallimento)';
COMMENT ON COLUMN plans.failure_reason IS 'Descrizione errore se status = FAILED';

-- ── 2. Plan Items (task nel DAG) ────────────────────────────────────────────

CREATE TABLE plan_items (
    id              UUID         PRIMARY KEY,
    plan_id         UUID         NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    ordinal         INTEGER      NOT NULL,
    task_key        VARCHAR(20)  NOT NULL,
    title           VARCHAR(500) NOT NULL,
    description     TEXT,
    worker_type     VARCHAR(20)  NOT NULL,
    worker_profile  VARCHAR(50),
    status          VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    result          TEXT,
    dispatched_at   TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    failure_reason  TEXT,
    UNIQUE (plan_id, task_key)
);

CREATE INDEX idx_plan_items_plan_status ON plan_items(plan_id, status);

COMMENT ON TABLE plan_items IS 'Task individuali nel DAG di un piano, con stato e risultato';
COMMENT ON COLUMN plan_items.id IS 'UUID univoco del task';
COMMENT ON COLUMN plan_items.plan_id IS 'Piano di appartenenza (FK cascade delete)';
COMMENT ON COLUMN plan_items.ordinal IS 'Ordine di inserimento nel piano (non determina esecuzione — quella e'' basata su dipendenze)';
COMMENT ON COLUMN plan_items.task_key IS 'Chiave breve leggibile, unica nel piano (es. BE-001, FE-002)';
COMMENT ON COLUMN plan_items.title IS 'Titolo descrittivo del task';
COMMENT ON COLUMN plan_items.description IS 'Descrizione dettagliata del lavoro da svolgere';
COMMENT ON COLUMN plan_items.worker_type IS 'Tipo di worker: BE, FE, CONTEXT_MANAGER, TASK_MANAGER, REVIEW, SCHEMA_MANAGER, SUB_PLAN';
COMMENT ON COLUMN plan_items.worker_profile IS 'Profilo stack-specific per routing (es. be-java, be-go). NULL = default da WorkerProfileRegistry';
COMMENT ON COLUMN plan_items.status IS 'Stato task: WAITING, DISPATCHED, RUNNING, DONE, FAILED, AWAITING_APPROVAL';
COMMENT ON COLUMN plan_items.result IS 'JSON risultato restituito dal worker al completamento';
COMMENT ON COLUMN plan_items.dispatched_at IS 'Timestamp invio al worker via messaging';
COMMENT ON COLUMN plan_items.completed_at IS 'Timestamp completamento (successo o fallimento)';
COMMENT ON COLUMN plan_items.failure_reason IS 'Descrizione errore se status = FAILED';

-- ── 3. Dipendenze tra task ──────────────────────────────────────────────────

CREATE TABLE plan_item_deps (
    item_id         UUID        NOT NULL REFERENCES plan_items(id) ON DELETE CASCADE,
    depends_on_key  VARCHAR(20) NOT NULL,
    PRIMARY KEY (item_id, depends_on_key)
);

COMMENT ON TABLE plan_item_deps IS 'Archi del DAG: un task dipende dal completamento di altri task (per task_key)';
COMMENT ON COLUMN plan_item_deps.item_id IS 'Task che dipende (FK cascade delete)';
COMMENT ON COLUMN plan_item_deps.depends_on_key IS 'task_key del task prerequisito';

-- ── 4. Quality Gate Reports ─────────────────────────────────────────────────

CREATE TABLE quality_gate_reports (
    id              UUID        PRIMARY KEY,
    plan_id         UUID        NOT NULL UNIQUE REFERENCES plans(id),
    passed          BOOLEAN     NOT NULL,
    summary         TEXT,
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE quality_gate_findings (
    report_id       UUID        NOT NULL REFERENCES quality_gate_reports(id) ON DELETE CASCADE,
    finding         TEXT        NOT NULL
);

COMMENT ON TABLE quality_gate_reports IS 'Risultato quality gate per piano (uno per piano, relazione 1:1)';
COMMENT ON COLUMN quality_gate_reports.plan_id IS 'Piano valutato (UNIQUE — un solo report per piano)';
COMMENT ON COLUMN quality_gate_reports.passed IS 'true = quality gate superato, false = violazioni trovate';
COMMENT ON COLUMN quality_gate_reports.summary IS 'Riepilogo testuale del risultato';
COMMENT ON TABLE quality_gate_findings IS 'Singole violazioni trovate dal quality gate';
COMMENT ON COLUMN quality_gate_findings.finding IS 'Descrizione della violazione';

-- ── 5. Dispatch Attempts (Command Pattern audit trail) ──────────────────────

CREATE TABLE dispatch_attempts (
    id              UUID        PRIMARY KEY,
    item_id         UUID        NOT NULL REFERENCES plan_items(id),
    attempt_number  INTEGER     NOT NULL,
    dispatched_at   TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ,
    success         BOOLEAN     NOT NULL DEFAULT FALSE,
    failure_reason  TEXT,
    duration_ms     BIGINT,
    UNIQUE (item_id, attempt_number)
);

CREATE INDEX idx_dispatch_attempts_item_id ON dispatch_attempts(item_id);

COMMENT ON TABLE dispatch_attempts IS 'Storico tentativi di dispatch per task (Command Pattern). Supporta retry e failure analysis';
COMMENT ON COLUMN dispatch_attempts.attempt_number IS 'Numero tentativo (1-based), crescente per item_id';
COMMENT ON COLUMN dispatch_attempts.success IS 'true = worker ha completato con successo';
COMMENT ON COLUMN dispatch_attempts.duration_ms IS 'Durata esecuzione worker in millisecondi';

-- ── 6. Plan Snapshots (Memento Pattern) ─────────────────────────────────────

CREATE TABLE plan_snapshots (
    id          UUID        PRIMARY KEY,
    plan_id     UUID        NOT NULL REFERENCES plans(id),
    label       VARCHAR(100) NOT NULL,
    plan_data   TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_plan_snapshots_plan_id ON plan_snapshots(plan_id);

COMMENT ON TABLE plan_snapshots IS 'Checkpoint del piano per crash recovery e debugging (Memento Pattern)';
COMMENT ON COLUMN plan_snapshots.label IS 'Etichetta del checkpoint (es. pre-dispatch, post-quality-gate)';
COMMENT ON COLUMN plan_snapshots.plan_data IS 'Serializzazione JSON completa dello stato piano + items';
