
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_tool (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT REFERENCES tp_project(id),
    name            VARCHAR(100) NOT NULL,
    type            SMALLINT     NOT NULL,
    category        VARCHAR(50),
    description     VARCHAR(500),
    body            TEXT,
    tags            JSONB,
    input_schema    JSONB,
    output_schema   JSONB,
    exec_kind       SMALLINT,
    exec_spec       JSONB,                                    -- {url,authRef}/{scriptEntry,lang}/{serverId,toolName}
    timeout_ms      INT,
    status          SMALLINT     NOT NULL DEFAULT 1,          -- 1=draft 2=pending_approval 3=published 4=rejected 5=retired
    current_version INT          NOT NULL DEFAULT 1,
    version         INT          NOT NULL DEFAULT 0,
    created_by      BIGINT,
    approved_by     BIGINT,
    approved_at     TIMESTAMP,
    reject_reason   VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_tool_type      CHECK (type IN (1, 2)),
    CONSTRAINT ck_tool_status    CHECK (status BETWEEN 1 AND 5),
    CONSTRAINT ck_tool_exec_kind CHECK (exec_kind IN (1, 2, 3))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tool_project_name ON tp_tool (COALESCE(project_id, 0), name);
CREATE INDEX IF NOT EXISTS idx_tool_status   ON tp_tool (status);
CREATE INDEX IF NOT EXISTS idx_tool_project  ON tp_tool (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tool_type     ON tp_tool (type);
CREATE INDEX IF NOT EXISTS idx_tool_category ON tp_tool (category) WHERE category IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tool_body_trgm ON tp_tool USING GIN (COALESCE(body, '') gin_trgm_ops);
COMMENT ON TABLE  tp_tool IS '工具注册（v2.16 U1-U3；DOC/EXECUTABLE 两型；状态机复用 S2；系统级 project_id NULL）';
COMMENT ON COLUMN tp_tool.status     IS 'S2 五态复用（common.approval）：1=draft 2=pending 3=published 4=rejected 5=retired；仅 published 可被项目启用（U4）';
COMMENT ON COLUMN tp_tool.type       IS '1=文档类 DOC（Markdown 正文纳管，U2）2=可执行 EXECUTABLE（exec 三形态，U3）';
COMMENT ON COLUMN tp_tool.exec_kind  IS '1=HTTP 回调 2=EXECUTOR 执行器沙箱 3=MCP_BRIDGE 转发（PRD ToolDefinition exec 三选）';
COMMENT ON COLUMN tp_tool.timeout_ms IS '执行超时毫秒（默认 30000，上限 600000=IT-TOOL-01 服务层守；T167 执行面消费）';
COMMENT ON COLUMN tp_tool.current_version IS '生效版本指针（裁定⑥：content 变更重审 +1，无 tp_tool_version 快照链）';
