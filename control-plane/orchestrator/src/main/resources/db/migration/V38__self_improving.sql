-- #182 — Self-Improving Prompt & Strategy Optimizer
-- Evolutionary prompt optimization (EvoPrompt) + BOHB strategy HPO + canary deployment.
-- Tracks prompt lineage, canary evaluations, and strategy configurations.

CREATE TABLE prompt_variants (
    id                UUID PRIMARY KEY,
    parent_id         UUID REFERENCES prompt_variants(id),
    worker_type       VARCHAR(50) NOT NULL,
    prompt_content    TEXT NOT NULL,
    generation        INT NOT NULL DEFAULT 0,
    fitness_score     DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    mutation_strategy VARCHAR(30),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prompt_variants_worker ON prompt_variants(worker_type, generation);
CREATE INDEX idx_prompt_variants_fitness ON prompt_variants(worker_type, fitness_score DESC);
CREATE INDEX idx_prompt_variants_parent ON prompt_variants(parent_id);

CREATE TABLE canary_results (
    id              UUID PRIMARY KEY,
    variant_id      UUID NOT NULL REFERENCES prompt_variants(id),
    task_count      INT NOT NULL DEFAULT 0,
    success_rate    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    significance_p  DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    promoted        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_canary_results_variant ON canary_results(variant_id);
CREATE INDEX idx_canary_results_promoted ON canary_results(promoted, created_at DESC);

CREATE TABLE strategy_configs (
    id                UUID PRIMARY KEY,
    config            JSONB NOT NULL,
    bohb_budget_used  INT NOT NULL DEFAULT 0,
    performance_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_strategy_configs_score ON strategy_configs(performance_score DESC);
