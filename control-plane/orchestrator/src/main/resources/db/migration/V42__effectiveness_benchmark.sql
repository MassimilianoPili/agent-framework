-- #181 — Longitudinal Effectiveness Benchmark
-- Time-series snapshots of framework effectiveness metrics.

CREATE TABLE effectiveness_snapshots (
    id              UUID PRIMARY KEY,
    bucket_start    TIMESTAMPTZ NOT NULL,
    bucket_end      TIMESTAMPTZ NOT NULL,
    metric_name     VARCHAR(50) NOT NULL,
    sample_count    INT NOT NULL DEFAULT 0,
    mean            DOUBLE PRECISION,
    p50             DOUBLE PRECISION,
    p95             DOUBLE PRECISION,
    stddev          DOUBLE PRECISION,
    raw_detail      JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_eff_snap_metric_bucket ON effectiveness_snapshots(metric_name, bucket_start DESC);
CREATE INDEX idx_eff_snap_created ON effectiveness_snapshots(created_at);
