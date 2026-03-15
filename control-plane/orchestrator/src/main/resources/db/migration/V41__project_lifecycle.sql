-- #180 — Multi-Plan Project Lifecycle Manager
-- Project container, plan-project linking, and saga sequencing with compensation.

CREATE TABLE projects (
    id            UUID PRIMARY KEY,
    name          VARCHAR(500) NOT NULL,
    description   TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'PLANNING',
    epic_specs    JSONB NOT NULL DEFAULT '[]'::jsonb,
    release_notes TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_projects_status ON projects(status);

CREATE TABLE project_plans (
    id            UUID PRIMARY KEY,
    project_id    UUID NOT NULL REFERENCES projects(id),
    plan_id       UUID NOT NULL REFERENCES plans(id) UNIQUE,
    epic_name     VARCHAR(200),
    ordinal       INT NOT NULL,
    sprint_number INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_project_plans_project ON project_plans(project_id, ordinal);
CREATE INDEX idx_project_plans_plan ON project_plans(plan_id);

CREATE TABLE project_saga_steps (
    id                  UUID PRIMARY KEY,
    project_id          UUID NOT NULL REFERENCES projects(id),
    plan_id             UUID NOT NULL REFERENCES plans(id),
    step_ordinal        INT NOT NULL,
    compensating_spec   TEXT,
    compensating_plan_id UUID REFERENCES plans(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_saga_steps_project ON project_saga_steps(project_id, step_ordinal);
CREATE INDEX idx_saga_steps_status ON project_saga_steps(status);
