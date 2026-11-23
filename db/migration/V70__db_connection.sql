
CREATE TABLE IF NOT EXISTS tp_db_connection (
    id           BIGSERIAL    PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    env_id       BIGINT       NOT NULL,
    name         VARCHAR(100) NOT NULL,
    dialect      VARCHAR(16)  NOT NULL,
    jdbc_url     VARCHAR(500) NOT NULL,
    username     VARCHAR(100) NOT NULL,
    password_enc TEXT         NOT NULL,
    readonly     BOOLEAN      NOT NULL DEFAULT true,
    max_pool     SMALLINT     NOT NULL DEFAULT 2,
    status       SMALLINT     NOT NULL DEFAULT 1,
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_db_connection_name ON tp_db_connection (project_id, name);
CREATE INDEX IF NOT EXISTS idx_db_connection_env ON tp_db_connection (env_id);

COMMENT ON TABLE tp_db_connection IS 'B11 数据库连接（密码密文 password_enc，生产环境禁建，主机过 ProdBlacklistGuard）';
