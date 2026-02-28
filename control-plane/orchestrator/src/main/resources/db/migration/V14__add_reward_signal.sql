-- V14: Reward signal system — multi-source Bayesian scoring + ELO ratings + DPO preference pairs

-- ── 1. Reward columns on plan_items ────────────────────────────────────────

ALTER TABLE plan_items
    ADD COLUMN review_score      FLOAT,
    ADD COLUMN process_score     FLOAT,
    ADD COLUMN aggregated_reward FLOAT,
    ADD COLUMN reward_sources    TEXT;

-- ── 2. Worker ELO rating statistics (one row per worker profile) ────────────

CREATE TABLE worker_elo_stats (
    worker_profile      VARCHAR(50)       PRIMARY KEY,
    elo_rating          DOUBLE PRECISION  NOT NULL DEFAULT 1600,
    match_count         INTEGER           NOT NULL DEFAULT 0,
    win_count           INTEGER           NOT NULL DEFAULT 0,
    cumulative_reward   DOUBLE PRECISION  NOT NULL DEFAULT 0,
    last_updated_at     TIMESTAMPTZ
);

-- ── 3. DPO preference pairs ─────────────────────────────────────────────────

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
