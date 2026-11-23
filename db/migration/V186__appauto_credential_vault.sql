
CREATE TABLE IF NOT EXISTS tp_credential (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL REFERENCES tp_project (id),
    name          VARCHAR(200) NOT NULL,
    cred_key      VARCHAR(64)  NOT NULL,
    secret_cipher TEXT         NOT NULL,
    status        SMALLINT     NOT NULL DEFAULT 1,
    remark        VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_credential_key UNIQUE (project_id, cred_key)
);

COMMENT ON TABLE tp_credential IS '凭据保险箱（-：口令永不回显/永不入步骤文本/永不进 artifact，密文列 AES-GCM 复用 CONFIG_AES_KEY 密钥链）';
COMMENT ON COLUMN tp_credential.cred_key IS '占位符引用键（{{CRED:key}} 消费面；白名单 ^[A-Za-z_][A-Za-z0-9_]*$ 服务层单点校验）';
COMMENT ON COLUMN tp_credential.secret_cipher IS '密文列：ENC: 前缀 + AES-GCM Base64（ConfigCipher 单源；明文禁入库，读取口一律打码）';
COMMENT ON COLUMN tp_credential.status IS '1=启用 0=禁用（S3 裁决默认=禁用下一会话生效，不打断在跑会话）';
COMMENT ON COLUMN tp_credential.remark IS '备注（用途说明：哪个 App 的登录账号/环境归属，防凭据误用）';

CREATE INDEX IF NOT EXISTS ix_credential_project
    ON tp_credential (project_id, status);
