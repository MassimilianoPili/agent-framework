-- ============================================================================
-- V2: Orchestrator Resilience
-- Retry automatico con backoff esponenziale, token budget per worker-type,
-- context management (issue snapshot, git state), pausing del piano.
-- ============================================================================

-- ── 1. Auto-retry + missing_context feedback loop ───────────────────────────

ALTER TABLE plan_items
    ADD COLUMN context_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at       TIMESTAMPTZ;

COMMENT ON COLUMN plan_items.context_retry_count IS 'Contatore re-queue per missing_context dal worker (max definito da manifest.maxContextRetries)';
COMMENT ON COLUMN plan_items.next_retry_at IS 'Prossimo tentativo ammesso da AutoRetryScheduler (backoff esponenziale con jitter ±25%)';

ALTER TABLE plans
    ADD COLUMN paused_at TIMESTAMPTZ;

COMMENT ON COLUMN plans.paused_at IS 'Timestamp pausa piano dopo N tentativi falliti (manifest.retry.attemptsBeforePause)';

CREATE INDEX idx_plan_items_retry
    ON plan_items (status, next_retry_at)
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;

-- ── 2. Token budget ─────────────────────────────────────────────────────────

ALTER TABLE plans
    ADD COLUMN budget_json TEXT;

COMMENT ON COLUMN plans.budget_json IS 'JSON budget: {onExceeded: FAIL_FAST|SOFT_LIMIT|NO_NEW_DISPATCH, perWorkerType: {BE: 200000, FE: 100000}}';

CREATE TABLE plan_token_usage (
    id           UUID        PRIMARY KEY,
    plan_id      UUID        NOT NULL REFERENCES plans(id),
    worker_type  VARCHAR(50) NOT NULL,
    tokens_used  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_plan_token_usage UNIQUE (plan_id, worker_type)
);

CREATE INDEX idx_plan_token_usage_plan ON plan_token_usage(plan_id);

COMMENT ON TABLE plan_token_usage IS 'Contatore atomico token consumati per piano e worker-type. Aggiornato via INCRBY';
COMMENT ON COLUMN plan_token_usage.tokens_used IS 'Token totali consumati (prompt + completion)';

-- ── 3. TASK_MANAGER issue snapshot ──────────────────────────────────────────

ALTER TABLE plan_items
    ADD COLUMN issue_snapshot TEXT;

COMMENT ON COLUMN plan_items.issue_snapshot IS 'JSON snapshot dell''issue dal tracker esterno, scritto dal TASK_MANAGER. NULL se non applicabile';

-- ── 4. Git state (cache key per ContextCacheInterceptor) ────────────────────

ALTER TABLE plans
    ADD COLUMN source_commit          VARCHAR(64),
    ADD COLUMN working_tree_diff_hash VARCHAR(64);

COMMENT ON COLUMN plans.source_commit IS 'Git SHA al momento della creazione piano. NULL se il piano non ha contesto git';
COMMENT ON COLUMN plans.working_tree_diff_hash IS 'SHA-256 del working-tree diff. Uguale a source_commit se l''albero e'' pulito';
