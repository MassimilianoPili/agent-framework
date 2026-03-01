-- ============================================================================
-- V10: Serendipity Context File Outcomes
-- Associa file suggeriti dal Context Manager ai task outcome con alto GP residual.
-- Usati per serendipity ranking: file storicamente utili per task simili.
-- Richiede V8 (task_outcomes).
-- ============================================================================

CREATE TABLE context_file_outcomes (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_outcome_id   UUID NOT NULL REFERENCES task_outcomes(id) ON DELETE CASCADE,
    plan_id           UUID NOT NULL,
    domain_task_key   VARCHAR(20) NOT NULL,
    file_path         VARCHAR(500) NOT NULL,
    residual          FLOAT NOT NULL,
    created_at        TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_cfo_outcome ON context_file_outcomes (task_outcome_id);
CREATE INDEX idx_cfo_residual ON context_file_outcomes (residual DESC);
CREATE INDEX idx_cfo_plan ON context_file_outcomes (plan_id);

COMMENT ON TABLE context_file_outcomes IS
    'File associati a task con GP residual alto. Usati per serendipity ranking.';
COMMENT ON COLUMN context_file_outcomes.task_outcome_id IS
    'FK a task_outcomes — la similarity search usa l''HNSW index di task_outcomes.task_embedding.';
COMMENT ON COLUMN context_file_outcomes.residual IS
    '|actual_reward - gp_mu| del domain task che ha usato questi file nel contesto.';
COMMENT ON COLUMN context_file_outcomes.file_path IS
    'Path relativo del file suggerito dal Context Manager (da relevant_files[].path nel result JSON).';
