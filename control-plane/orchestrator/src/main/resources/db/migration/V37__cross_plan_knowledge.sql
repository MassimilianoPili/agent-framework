-- #178 — Cross-Plan Knowledge Transfer Engine
-- Three knowledge channels: prompt refinements, error patterns (Reflexion), architectural decisions.
-- Plus ExpeL-style generalizable insights extracted post-plan.

CREATE TABLE prompt_refinements (
    id              UUID PRIMARY KEY,
    archetype       VARCHAR(200) NOT NULL,
    worker_type     VARCHAR(50) NOT NULL,
    prompt_variant  TEXT NOT NULL,
    prm_score_avg   DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    usage_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prompt_refinements_archetype ON prompt_refinements(archetype, worker_type);
CREATE INDEX idx_prompt_refinements_score ON prompt_refinements(prm_score_avg DESC);

CREATE TABLE error_patterns (
    id                  UUID PRIMARY KEY,
    context_embedding   vector(1024),
    error_description   TEXT NOT NULL,
    correction          TEXT NOT NULL,
    confidence          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    outcome             VARCHAR(50),
    plan_id             UUID REFERENCES plans(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_error_patterns_plan ON error_patterns(plan_id);
CREATE INDEX idx_error_patterns_confidence ON error_patterns(confidence DESC);
-- HNSW index for cosine similarity search on embeddings
CREATE INDEX idx_error_patterns_embedding ON error_patterns
    USING hnsw (context_embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

CREATE TABLE arch_decisions (
    id                  UUID PRIMARY KEY,
    context             TEXT NOT NULL,
    decision            TEXT NOT NULL,
    rationale           TEXT,
    outcome_score       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    plan_id             UUID REFERENCES plans(id),
    council_session_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_arch_decisions_plan ON arch_decisions(plan_id);
CREATE INDEX idx_arch_decisions_outcome ON arch_decisions(outcome_score DESC);

CREATE TABLE plan_insights (
    id                  UUID PRIMARY KEY,
    plan_id             UUID REFERENCES plans(id),
    insight_text        TEXT NOT NULL,
    category            VARCHAR(50) NOT NULL,
    applicability_score DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_plan_insights_plan ON plan_insights(plan_id);
CREATE INDEX idx_plan_insights_category ON plan_insights(category);
CREATE INDEX idx_plan_insights_score ON plan_insights(applicability_score DESC);
