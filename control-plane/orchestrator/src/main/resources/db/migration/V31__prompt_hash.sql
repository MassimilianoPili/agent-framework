-- #48: Content-Addressable Storage completion — persist prompt hash on plan items.
-- The prompt SHA-256 is computed by the worker (AbstractWorker) and propagated
-- via AgentResult.promptHash. Stored here for traceability and CAS lookup.
ALTER TABLE plan_items ADD COLUMN prompt_hash VARCHAR(64);
