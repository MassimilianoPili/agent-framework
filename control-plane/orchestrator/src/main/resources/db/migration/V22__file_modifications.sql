-- G3: File Modification Tracking — record every file written/created/deleted by workers
CREATE TABLE file_modifications (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         UUID NOT NULL,
    item_id         UUID NOT NULL REFERENCES plan_items(id),
    task_key        VARCHAR(255) NOT NULL,
    file_path       VARCHAR(1024) NOT NULL,
    operation       VARCHAR(20) NOT NULL,
    content_hash_before VARCHAR(64),
    content_hash_after  VARCHAR(64),
    diff_preview    TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_file_mod_item ON file_modifications(item_id);
CREATE INDEX idx_file_mod_plan ON file_modifications(plan_id);
