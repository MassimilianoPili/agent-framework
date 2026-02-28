-- V9: TASK_MANAGER issue snapshot
--
-- issue_snapshot: JSON blob written by the TASK_MANAGER worker after it
--                 fetches the canonical issue from the external tracker.
--                 Nullable — populated only when TASK_MANAGER runs.

ALTER TABLE plan_items
    ADD COLUMN issue_snapshot TEXT;
