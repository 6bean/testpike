-- Migration: the process-event ledger gains a ciphertext
-- sidecar column (a masked display value can later be resolved through the
-- gated reveal door with an audit row; plaintext never rides the detail
-- body), and the two exchange-trail size knobs become hot config.
-- Applied manually to the dev DB (Flyway not enabled; db/migration is the
-- reference + replay source for schema-init, regenerated via
-- tools/ops/gen_schema_init.sh).
ALTER TABLE tp_ai_task_event ADD COLUMN IF NOT EXISTS sealed TEXT;
COMMENT ON COLUMN tp_ai_task_event.sealed IS 'ENC:-prefixed AES-GCM ciphertext sidecar of secret values echoed in masked form inside detail; the reveal door decrypts it after login + project-membership checks and writes an audit row; plain detail itself never carries plaintext credentials';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ui.tail.detail-max', '16384', '系统', 'int', 0, '过程详情每一步正文的显示上限字符数（默认 16384，保存即生效；超出的部分不丢弃，事件里注明总字节数并提供「展开全文」入口）'),
    ('session.probe.body-max-bytes', '65536', '系统', 'int', 0, '立即测试/开始探测记录的响应体字节上限（默认 65536，保存即生效；超出时显示「共 N 字节，可展开全文」，不会静默截尾）')
ON CONFLICT (config_key) DO NOTHING;
