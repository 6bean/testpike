-- 02 /api-tokens GET/POST + /api-tokens/{id}/revoke DELETE +

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_api_token (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES tp_user (id),
    name         VARCHAR(100) NOT NULL,
    token_hash   CHAR(64)     NOT NULL UNIQUE,
    scopes       VARCHAR(300) NOT NULL,
    expires_at   TIMESTAMP,
    revoked      SMALLINT     NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_api_token_revoked CHECK (revoked IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_api_token_user ON tp_api_token (user_id);
COMMENT ON TABLE tp_api_token IS 'API Token（G6 自身 MCP Server 认证；SHA-256 哈希存储+scope+过期+吊销，明文仅创建展示一次）';
COMMENT ON COLUMN tp_api_token.token_hash IS 'SHA-256(明文) 小写 hex（DigestHex 件；日志/出参禁明文，列表仅前 8 位指纹=02 /api-tokens 口径）';
COMMENT ON COLUMN tp_api_token.scopes IS '逗号分隔 scope 集（PRD 钉 mcp. 前缀；scope 外工具 tools/list 不可见=）';
COMMENT ON COLUMN tp_api_token.revoked    IS '吊销终态 1/0（02 DELETE /{id}/revoke；Redis 吊销缓存即时生效 R6 模式归 api 侧）';
