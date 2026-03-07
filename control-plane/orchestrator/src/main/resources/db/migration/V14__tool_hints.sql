-- Tool hints: per-task MCP tool allowlist suggested by planner (#24L1)
CREATE TABLE plan_item_tool_hints (
    item_id UUID NOT NULL REFERENCES plan_items(id),
    tool_hint VARCHAR(100) NOT NULL
);
CREATE INDEX idx_tool_hints_item ON plan_item_tool_hints(item_id);
