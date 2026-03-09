-- #31 Verifiable Compute: Ed25519 worker public key registry
-- Stores trusted public keys for signature verification (TOFU + registered)

CREATE TABLE worker_keys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    worker_type      VARCHAR(50)  NOT NULL,
    worker_profile   VARCHAR(100),
    public_key_base64 VARCHAR(256) NOT NULL UNIQUE,
    registered_at    TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMP    NOT NULL DEFAULT now(),
    disabled         BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_worker_keys_pubkey ON worker_keys(public_key_base64) WHERE NOT disabled;
CREATE INDEX idx_worker_keys_type   ON worker_keys(worker_type) WHERE NOT disabled;
