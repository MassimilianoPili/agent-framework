-- #44 Shared workspace directory per plan
-- Workers bind-mount /workspace/{workspace_volume}/ for code generation and review.
-- Null until workspace is created; set to null after cleanup.

ALTER TABLE plans ADD COLUMN workspace_volume VARCHAR(100);
