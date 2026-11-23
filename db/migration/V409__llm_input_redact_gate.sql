-- Pre-send reasoning-input redaction gate (behaviour kernel second round,
-- item 2): credential / identity word forms are masked out of reasoning
-- payloads before they leave the system. Two governance rows ship together:
-- the master switch (factory ON — the shipped posture is that nothing
-- sensitive is ever sent unmasked, no configuration needed) and the
-- word-form set key (empty by default = only the built-in narrow forms are
-- in force: API key, cloud access key, secret assignment, identity card,
-- mobile number; extra "form=regex" lines merge in, unparseable lines are
-- skipped). Both rows are read hot per send — changes land on the next
-- request, no restart. Masking transforms only the outbound payload; the
-- originals stored in the system are never rewritten.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.llm.redact-enabled', 'true', '系统', 'bool', 0, '推理输入发送前脱敏开关（默认开）：开启时，发往模型的文本里的密钥、身份证号、手机号等形态会被替换为「已脱敏」标记，系统内存储的原文不变；关闭后不再遮蔽，但若输入仍含这些形态，本轮发送会被拒绝并提示开启位置'),
    ('ai.llm.redact.patterns', '', '系统', 'text', 0, '脱敏词形追加规则（默认空=仅内置形态生效）：每行一条「形态名=正则表达式」，与内置的密钥、身份证号、手机号形态叠加生效；无法解析的行会被忽略。修改后下一次发送即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
