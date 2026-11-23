-- Migration: K8 config key for the per-question reference cap .
--
-- Why this file exists:
--   @-mention chips now ride the dialog chain (admin → core → broker →
--   ai-service). Core canonicalises them once; this key is the single cap of
--   how many objects one question may pin. The write side guards the interval
--   in AiChatKeyRanges (K8 convention: no DB CHECK, service layer is the
--   single source); the reader falls back to the seeded default (10) on a
--   missing or garbage value, and an overrun request is refused with a reason
--   naming both numbers — never a silent truncation of what the user pinned.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.ref.max-objects', '20', '系统', 'int', 0, '单条提问最多引用的对象数量（默认 10；区间 1~20，热更即生效；超出数量时提问会被拒绝并提示当前引用个数，不会悄悄丢掉一部分）')
ON CONFLICT (config_key) DO NOTHING;
