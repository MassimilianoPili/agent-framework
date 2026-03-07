-- ============================================================================
-- V11: Optimistic Locking
-- Adds @Version column to plans and plan_items for JPA optimistic locking.
-- Prevents lost updates when concurrent threads modify the same entity
-- (e.g. onTaskCompleted racing with AutoRetryScheduler on the same PlanItem).
-- ============================================================================

ALTER TABLE plans ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE plan_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Project path for dynamic ownsPaths resolution.
-- Workers receive this path in AgentTask.dynamicOwnsPaths to merge with
-- their static ownsPaths from the manifest, enabling project-aware path enforcement.
ALTER TABLE plans ADD COLUMN project_path VARCHAR(500);
