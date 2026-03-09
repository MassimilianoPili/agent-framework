-- #32: Policy-as-Code Immutabile — SHA-256 commitment hash of HookPolicy
-- Stores the canonical JSON hash computed at policy storage time.
-- NULL for tasks without HOOK_MANAGER-generated policy.
ALTER TABLE plan_items ADD COLUMN policy_hash VARCHAR(64);
