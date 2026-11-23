-- Migration: — scroll-frame budget for the windowed chat thread.
-- One scroll frame longer than this budget is surfaced as an honest ledger
-- attribute on the scroller (data-scroll-over) instead of being silently
-- swallowed; the windowed DOM bound itself is already contract-tested at
-- TC-AO-06 scale. Read side falls back to the default below when the key is
-- absent, and ignores out-of-range values; write side clamps 1~1000. Applied
-- manually to the dev DB (Flyway not enabled; db/migration is reference +
-- replay source for schema-init, regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('chat.scroll-budget-ms', '16', '系统', 'int', 0, '对话长会话滚动帧预算毫秒数（默认 16=60fps；可设 1~1000，热更即生效；连续滚动帧超过预算时该轮会话会如实标记为未达标，供性能体检读取）')
ON CONFLICT (config_key) DO NOTHING;
