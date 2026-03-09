-- #26L2: Auto-split for expensive tasks
-- estimated_input_tokens: pre-dispatch heuristic estimate (for calibration and split decisions)
-- split_attempt_count: guard against infinite split loops (max 1 by default)
ALTER TABLE plan_items ADD COLUMN estimated_input_tokens BIGINT;
ALTER TABLE plan_items ADD COLUMN split_attempt_count INTEGER NOT NULL DEFAULT 0;
