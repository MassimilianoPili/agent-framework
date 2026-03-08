-- #48 Content-Addressable Storage for worker artifacts
-- SHA-256 keyed store with automatic deduplication and integrity verification.

CREATE TABLE artifact_store (
    content_hash  VARCHAR(64)  PRIMARY KEY,
    content       TEXT         NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    access_count  BIGINT       NOT NULL DEFAULT 1
);

COMMENT ON TABLE artifact_store IS 'CAS: content-addressable artifact storage, SHA-256 keyed';

-- Link plan_items to CAS (nullable — backward compatible)
ALTER TABLE plan_items
    ADD COLUMN result_hash VARCHAR(64) REFERENCES artifact_store(content_hash);

-- Backfill: hash existing non-null results into artifact_store
INSERT INTO artifact_store (content_hash, content, size_bytes, created_at, access_count)
SELECT
    encode(sha256(result::bytea), 'hex'),
    result,
    octet_length(result),
    COALESCE(completed_at, NOW()),
    1
FROM plan_items
WHERE result IS NOT NULL
ON CONFLICT (content_hash) DO UPDATE
    SET access_count = artifact_store.access_count + 1;

-- Set result_hash for existing items
UPDATE plan_items
SET result_hash = encode(sha256(result::bytea), 'hex')
WHERE result IS NOT NULL;
