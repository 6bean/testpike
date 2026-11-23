
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_mcp_server (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(100)  NOT NULL UNIQUE,
    endpoint       VARCHAR(500)  NOT NULL,
    transport      SMALLINT      NOT NULL,
    auth_enc       TEXT,
    tools_json     JSONB,
    enabled        SMALLINT      NOT NULL DEFAULT 1,
    health_status  SMALLINT      NOT NULL DEFAULT 0,
    health_message VARCHAR(500),
    last_probe_at  TIMESTAMP,
    created_at     TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT ck_mcp_server_transport CHECK (transport IN (1, 2)),
    CONSTRAINT ck_mcp_server_enabled   CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_mcp_server_health    CHECK (health_status IN (0, 1, 2))
);
CREATE INDEX IF NOT EXISTS idx_mcp_server_enabled ON tp_mcp_server (enabled);
COMMENT ON TABLE tp_mcp_server IS 'MCP 服务注册（G7 调用面；endpoint/transport/加密auth/tools_json 白名单快照/启停/健康列）';
COMMENT ON COLUMN tp_mcp_server.transport IS '1=sse 2=streamable-http（连接测试 initialize 握手按此协议形态）';
COMMENT ON COLUMN tp_mcp_server.auth_enc IS '鉴权凭证密文（复用 K9=ConfigCipher；日志/出参禁明文， 打码不回显）';
COMMENT ON COLUMN tp_mcp_server.tools_json IS 'tools/list 实拉快照+白名单勾选（enabled 内嵌，默认全拒；勾选集外调用 -32601=）';
COMMENT ON COLUMN tp_mcp_server.health_status IS '0=未测试 1=健康 2=失败（不可达/握手超时5s/非法JSON-RPC 三分类可读入 health_message）';
