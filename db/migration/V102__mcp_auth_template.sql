
CREATE TABLE IF NOT EXISTS tp_mcp_auth_template (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    tools       JSONB NOT NULL DEFAULT '[]'::jsonb,
    remark      VARCHAR(500),
    scope       SMALLINT NOT NULL DEFAULT 1,
    project_id  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mcp_auth_template_name UNIQUE (name),
    CONSTRAINT ck_mcp_auth_template_scope CHECK (scope IN (1, 2))
);
COMMENT ON TABLE tp_mcp_auth_template IS 'G5 工具授权模板（tools=工具名清单 JSONB 数组；scope 1=系统级 2=项目级）';
COMMENT ON COLUMN tp_mcp_auth_template.tools IS '工具名清单（字符串数组；套用时与目标服务快照交集，快照外点名跳过）';
COMMENT ON COLUMN tp_mcp_auth_template.scope IS '1=系统级（跨项目复用） 2=项目级（project_id 必填）';

CREATE TABLE IF NOT EXISTS tp_project_mcp_auth (
    project_id   BIGINT PRIMARY KEY,
    template_id  BIGINT NOT NULL,
    enabled      SMALLINT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_project_mcp_auth_enabled CHECK (enabled IN (0, 1))
);
COMMENT ON TABLE tp_project_mcp_auth IS '项目 MCP 工具继承开关（1=装配按模板交集 0=零工具；无行=直通零漂移）';
CREATE INDEX IF NOT EXISTS idx_project_mcp_auth_template ON tp_project_mcp_auth (template_id);
