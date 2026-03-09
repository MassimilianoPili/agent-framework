-- #30: Hash Chain Tamper-Proof on plan_event
-- Adds cryptographic hash chain fields for integrity verification.
-- Each event stores its own SHA-256 hash and the hash of the previous event,
-- forming a tamper-evident chain similar to a blockchain.

ALTER TABLE plan_event ADD COLUMN event_hash VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE plan_event ADD COLUMN previous_hash VARCHAR(64) NOT NULL DEFAULT '';

-- Note: existing events will have empty hashes (default '').
-- New events appended after this migration will have proper hashes.
-- The HashChainVerifier skips verification of events with empty hashes
-- (pre-migration events) and verifies the chain from the first hashed event onward.
-- A one-time backfill can be triggered via the /api/v1/plans/{id}/verify-integrity endpoint
-- after running the HashChainBackfillService (future enhancement).
