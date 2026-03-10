-- #45 — Merkle Tree per DAG Verification
-- dagHash: SHA-256 of (taskKey|workerType|title|sorted predecessor hashes)
-- merkleRoot: SHA-256 of sorted sink-node dagHashes

ALTER TABLE plan_items ADD COLUMN dag_hash VARCHAR(64);
ALTER TABLE plans ADD COLUMN merkle_root VARCHAR(64);
