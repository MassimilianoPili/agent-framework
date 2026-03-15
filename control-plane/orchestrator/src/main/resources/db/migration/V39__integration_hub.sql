-- #184 — External System Integration Hub
-- Inbound/outbound event log with idempotency dedup + configurable notification rules.

CREATE TABLE integration_events (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(200) UNIQUE,
    direction       VARCHAR(10) NOT NULL,
    system_type     VARCHAR(50) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    plan_id         UUID REFERENCES plans(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ
);

CREATE INDEX idx_integration_events_idempotency ON integration_events(idempotency_key);
CREATE INDEX idx_integration_events_plan ON integration_events(plan_id);
CREATE INDEX idx_integration_events_status ON integration_events(status, created_at);
CREATE INDEX idx_integration_events_direction ON integration_events(direction, system_type);

CREATE TABLE notification_rules (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(200) NOT NULL,
    event_type_pattern VARCHAR(200) NOT NULL,
    target_system      VARCHAR(50) NOT NULL,
    target_config      JSONB NOT NULL,
    template           TEXT,
    severity_filter    VARCHAR(20),
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_rules_enabled ON notification_rules(enabled, event_type_pattern);
