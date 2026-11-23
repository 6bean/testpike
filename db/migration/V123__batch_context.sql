
CREATE TABLE IF NOT EXISTS tp_batch_context (
    id           bigserial PRIMARY KEY,
    batch_id     bigint       NOT NULL,
    ctx_key      varchar(128) NOT NULL,
    value_cipher text         NOT NULL,
    updated_at   timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT uq_batch_context_key UNIQUE (batch_id, ctx_key)
);

CREATE INDEX IF NOT EXISTS idx_batch_context_batch
    ON tp_batch_context (batch_id);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('batch.context-ttl-seconds', '1800', '系统', 'int', 0, '批次上下文 Redis 读穿 TTL 秒（DB 权威行不过期；缺键回退默认恒等）')
ON CONFLICT (config_key) DO NOTHING;
