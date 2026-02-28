-- V10: Git state snapshot on plans (used by context cache key construction)
--
-- source_commit:        Git SHA at plan creation time (nullable — not all plans have git context)
-- working_tree_diff_hash: SHA-256 of the working-tree diff; equals source_commit when tree is clean.
--
-- Together these two fields allow the ContextCacheInterceptor to build a stable cache key:
--   sha256(workerType + ":" + contextJson)
-- The contextJson already includes TASK_MANAGER's issueSnapshot, so changes to either the
-- issue or the commit state naturally produce different cache keys and force fresh LLM calls.

ALTER TABLE plans
    ADD COLUMN source_commit          VARCHAR(64),
    ADD COLUMN working_tree_diff_hash VARCHAR(64);
