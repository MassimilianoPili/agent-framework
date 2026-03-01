-- ============================================================================
-- V8: GP Task Outcomes
-- Tabella training per Gaussian Process: embedding + reward + predizione GP.
-- Richiede estensione pgvector (creata in V5).
-- ============================================================================

CREATE TABLE task_outcomes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_item_id    UUID REFERENCES plan_items(id) ON DELETE SET NULL,
    plan_id         UUID NOT NULL,
    task_key        VARCHAR(20) NOT NULL,
    worker_type     VARCHAR(20) NOT NULL,
    worker_profile  VARCHAR(50),
    task_embedding  vector(1024),
    elo_at_dispatch FLOAT,
    gp_mu           FLOAT,
    gp_sigma2       FLOAT,
    actual_reward   FLOAT,
    created_at      TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_task_outcomes_embedding ON task_outcomes
    USING hnsw (task_embedding vector_cosine_ops);
CREATE INDEX idx_task_outcomes_worker ON task_outcomes (worker_type, worker_profile);
CREATE INDEX idx_task_outcomes_created ON task_outcomes (created_at DESC);

COMMENT ON TABLE task_outcomes IS 'Training data per GP: embedding, reward, predizione al dispatch';
COMMENT ON COLUMN task_outcomes.task_embedding IS 'Embedding mxbai-embed-large (1024 dim) del titolo+descrizione del task';
COMMENT ON COLUMN task_outcomes.elo_at_dispatch IS 'Snapshot ELO del worker_profile al momento del dispatch';
COMMENT ON COLUMN task_outcomes.gp_mu IS 'Predizione GP (media posteriore) al momento del dispatch';
COMMENT ON COLUMN task_outcomes.gp_sigma2 IS 'Incertezza GP (varianza posteriore) al momento del dispatch';
COMMENT ON COLUMN task_outcomes.actual_reward IS 'plan_items.aggregated_reward post-completion (NULL finche il task non termina)';
