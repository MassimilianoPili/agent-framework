-- ============================================================================
-- V4: Council Pre-Planning + Reward Signal
-- Report del council pre-planning. Sistema di reward multi-source:
-- scoring Bayesiano, rating ELO per worker, preference pairs DPO per training.
-- ============================================================================

-- ── 1. Council report ───────────────────────────────────────────────────────

ALTER TABLE plans
    ADD COLUMN council_report TEXT;

COMMENT ON COLUMN plans.council_report IS 'JSON CouncilReport dal pre-planning council session. NULL se council.enabled=false';

-- ── 2. Reward signal su plan_items ──────────────────────────────────────────

ALTER TABLE plan_items
    ADD COLUMN review_score      FLOAT,
    ADD COLUMN process_score     FLOAT,
    ADD COLUMN aggregated_reward FLOAT,
    ADD COLUMN reward_sources    TEXT;

COMMENT ON COLUMN plan_items.review_score IS 'Score 0-1 dalla review automatica (quality gate, test pass rate, code quality)';
COMMENT ON COLUMN plan_items.process_score IS 'Score 0-1 dal processo (rispetto budget token, tempo, retry count)';
COMMENT ON COLUMN plan_items.aggregated_reward IS 'Reward aggregato Bayesiano: weighted average di review_score e process_score';
COMMENT ON COLUMN plan_items.reward_sources IS 'JSON dettaglio fonti: [{source: "review", weight: 0.7, score: 0.85}, ...]';

-- ── 3. Worker ELO rating (un record per profilo worker) ─────────────────────

CREATE TABLE worker_elo_stats (
    worker_profile      VARCHAR(50)       PRIMARY KEY,
    elo_rating          DOUBLE PRECISION  NOT NULL DEFAULT 1600,
    match_count         INTEGER           NOT NULL DEFAULT 0,
    win_count           INTEGER           NOT NULL DEFAULT 0,
    cumulative_reward   DOUBLE PRECISION  NOT NULL DEFAULT 0,
    last_updated_at     TIMESTAMPTZ
);

COMMENT ON TABLE worker_elo_stats IS 'Rating ELO per profilo worker. Aggiornato dopo ogni task completato';
COMMENT ON COLUMN worker_elo_stats.worker_profile IS 'Identificatore profilo (es. be-java, fe-react, review-standard)';
COMMENT ON COLUMN worker_elo_stats.elo_rating IS 'Rating ELO corrente (iniziale: 1600). Usato per routing preferenziale';
COMMENT ON COLUMN worker_elo_stats.match_count IS 'Numero totale di task completati (vinti + persi)';
COMMENT ON COLUMN worker_elo_stats.win_count IS 'Numero di task con aggregated_reward >= soglia di successo';
COMMENT ON COLUMN worker_elo_stats.cumulative_reward IS 'Somma di tutti gli aggregated_reward (per media mobile)';

-- ── 4. DPO preference pairs (training data) ────────────────────────────────

CREATE TABLE preference_pairs (
    id                UUID          PRIMARY KEY,
    plan_id           UUID          REFERENCES plans(id),
    task_key          VARCHAR(20)   NOT NULL,
    worker_type       VARCHAR(20)   NOT NULL,
    prompt_text       TEXT          NOT NULL,
    chosen_result     TEXT          NOT NULL,
    rejected_result   TEXT          NOT NULL,
    chosen_reward     FLOAT         NOT NULL,
    rejected_reward   FLOAT         NOT NULL,
    delta_reward      FLOAT         NOT NULL,
    generation_source VARCHAR(50),
    created_at        TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_pp_worker_type ON preference_pairs (worker_type);
CREATE INDEX idx_pp_delta       ON preference_pairs (delta_reward DESC);
CREATE INDEX idx_pp_plan_id     ON preference_pairs (plan_id);

COMMENT ON TABLE preference_pairs IS 'Coppie DPO (Direct Preference Optimization): chosen vs rejected per fine-tuning';
COMMENT ON COLUMN preference_pairs.prompt_text IS 'Prompt originale inviato al worker';
COMMENT ON COLUMN preference_pairs.chosen_result IS 'Risultato preferito (reward piu'' alto)';
COMMENT ON COLUMN preference_pairs.rejected_result IS 'Risultato scartato (reward piu'' basso)';
COMMENT ON COLUMN preference_pairs.delta_reward IS 'Differenza reward (chosen - rejected). Ordinamento DESC per qualita'' training data';
COMMENT ON COLUMN preference_pairs.generation_source IS 'Origine della coppia: automatic (retry), manual (human review), synthetic';
