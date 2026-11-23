
CREATE TABLE IF NOT EXISTS tp_project_tool (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT   NOT NULL REFERENCES tp_project(id) ON DELETE CASCADE,
    tool_id    BIGINT   NOT NULL REFERENCES tp_tool(id) ON DELETE CASCADE,
    enabled    SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_project_tool UNIQUE (project_id, tool_id),
    CONSTRAINT ck_project_tool_enabled CHECK (enabled IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_project_tool_tool ON tp_project_tool (tool_id);
COMMENT ON TABLE tp_project_tool IS '项目工具启用（U4 数据基座；写端点/消费链归 ， 仅落表+删除引用保护）';
