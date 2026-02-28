-- Hierarchical plans (SUB_PLAN worker type, #9).

-- plan_items: SUB_PLAN fields
ALTER TABLE plan_items
    ADD COLUMN child_plan_id     UUID    REFERENCES plans(id),
    ADD COLUMN await_completion  BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN sub_plan_spec     TEXT;

-- plans: depth and parent tracking
ALTER TABLE plans
    ADD COLUMN depth          INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN parent_plan_id UUID    REFERENCES plans(id);

CREATE INDEX idx_plan_items_child_plan ON plan_items(child_plan_id)
    WHERE child_plan_id IS NOT NULL;
CREATE INDEX idx_plans_parent ON plans(parent_plan_id)
    WHERE parent_plan_id IS NOT NULL;
