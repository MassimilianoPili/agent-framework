-- V18: model_id column on plan_items (#20)
-- Optional LLM model ID override per task.
-- Null = use the worker's default model.
-- Example values: 'claude-haiku-4-5-20251001', 'claude-opus-4-6'

ALTER TABLE plan_items ADD COLUMN model_id VARCHAR(100);

COMMENT ON COLUMN plan_items.model_id IS
    'Optional LLM model ID override (null = worker default). E.g. claude-haiku-4-5-20251001';
