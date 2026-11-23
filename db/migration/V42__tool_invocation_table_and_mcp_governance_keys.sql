
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_tool_invocation (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    caller       VARCHAR(10)  NOT NULL,
    actor_id     BIGINT,
    task_id      BIGINT,
    tool_id      BIGINT,
    server_id    BIGINT,
    tool_name    VARCHAR(100) NOT NULL,
    success      SMALLINT     NOT NULL,
    duration_ms  BIGINT,
    error        VARCHAR(500),
    digest_hash  CHAR(64),
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_tool_invocation_caller CHECK (caller IN ('mcp', 'ai', 'user')),
    CONSTRAINT ck_tool_invocation_success CHECK (success IN (0, 1))
);

CREATE INDEX IF NOT EXISTS idx_tool_invocation_task  ON tp_tool_invocation (task_id)  WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tool_invocation_time  ON tp_tool_invocation (server_id, created_at);
CREATE INDEX IF NOT EXISTS idx_tool_invocation_tool  ON tp_tool_invocation (tool_id, created_at);

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('mcp.rate-limit/min', '30',  '系统', 'int',  0, 'MCP Server 端点每分钟调用上限（T164 消费；Redis 窗口计数）'),
('mcp.readonly',       'false', '系统', 'bool', 0, 'MCP 只读开关（T164 消费；开=禁写工具注册）'),
('mcp.call-budget',    '8',   '系统', 'int',  0, '单次生成任务工具调用预算（T163 消费；超限熔断可读「工具调用超预算」，TC-MCP-02）')
ON CONFLICT (config_key) DO NOTHING;
