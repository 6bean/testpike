-- Migration: segment e — terminal dwell knob of the process tail window
-- (R62). The end-state frame must render and stay readable before the
-- drawer auto-collapses; the frontend reads this key once per page load
-- (frontend/src/composables/tailWindowConfig.ts) and falls back to the
-- factory default below when a row is absent or out of range. Applied
-- manually to the dev DB (Flyway not enabled; db/migration is reference +
-- replay source for schema-init, regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ui.tail.terminal-dwell-ms', '1500', '系统', 'int', 0, '过程详情结束后，最后一条结果在面板上保持显示的毫秒数，到时自动关闭面板（默认 1500；可设 0~10000，保存即生效；0 表示结果出现后立即关闭）')
ON CONFLICT (config_key) DO NOTHING;
