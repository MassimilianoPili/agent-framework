-- V7: Auto-retry scheduler + missing_context feedback loop
--
-- context_retry_count: tracks how many times a task was re-queued for missing context
-- next_retry_at:       earliest time AutoRetryScheduler may re-dispatch a FAILED item
-- paused_at:           timestamp when the plan was paused due to attemptsBeforePause threshold

ALTER TABLE plan_items
    ADD COLUMN context_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at       TIMESTAMPTZ;

ALTER TABLE plans
    ADD COLUMN paused_at           TIMESTAMPTZ;

-- Index to support efficient AutoRetryScheduler polling
CREATE INDEX idx_plan_items_retry
    ON plan_items (status, next_retry_at)
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;
