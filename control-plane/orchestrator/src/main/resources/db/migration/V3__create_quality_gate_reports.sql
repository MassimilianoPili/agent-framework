CREATE TABLE quality_gate_reports (
    id              UUID        PRIMARY KEY,
    plan_id         UUID        NOT NULL UNIQUE REFERENCES plans(id),
    passed          BOOLEAN     NOT NULL,
    summary         TEXT,
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE quality_gate_findings (
    report_id       UUID        NOT NULL REFERENCES quality_gate_reports(id) ON DELETE CASCADE,
    finding         TEXT        NOT NULL
);
