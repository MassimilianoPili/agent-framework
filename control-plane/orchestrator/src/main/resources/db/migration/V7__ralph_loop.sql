-- V7: Ralph-Loop — quality gate feedback loop
-- Adds columns to plan_items for tracking ralph-loop retries and feedback.

ALTER TABLE plan_items
    ADD COLUMN IF NOT EXISTS ralph_loop_count            INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_quality_gate_feedback   TEXT;
