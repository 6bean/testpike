-- Migration: stalled marker threshold for batch progress re-reads (W11).
-- When consecutive reads of a batch's progress fail (an expired sign-in being
-- the proven cause), the page shows a stalled hint with the repair entry
-- instead of freezing silently. The UI knob is read from the single
-- tail-window config source (frontend/src/composables/tailWindowConfig.ts)
-- and falls back to the factory default below when a row is absent or out of
-- range. Applied manually to the dev DB (Flyway not enabled; db/migration is
-- reference + replay source for schema-init, regenerated via
-- tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ui.tail.poll-failure-threshold', '3', '系统', 'int', 0, '批次进度连续多少次读取失败后，页面显示「进度已停止更新」提示（默认 3；可设 2~20，保存即生效；提示会给出处理入口，恢复后自动继续更新）')
ON CONFLICT (config_key) DO NOTHING;
