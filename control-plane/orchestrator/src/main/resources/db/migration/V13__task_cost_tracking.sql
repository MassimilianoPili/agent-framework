-- Per-task token usage and cost estimation (#26L1)
ALTER TABLE plan_items ADD COLUMN input_tokens  BIGINT;
ALTER TABLE plan_items ADD COLUMN output_tokens BIGINT;
ALTER TABLE plan_items ADD COLUMN estimated_cost_usd NUMERIC(10, 6);
