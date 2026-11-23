-- Migration: segment d/e — hot-updated knobs of the sliding process-tail
-- window (R62 first sample). The UI reads all three once per page load
-- (frontend/src/composables/tailWindowConfig.ts) and falls back to the
-- factory defaults below when a row is absent or out of range; the read
-- side never amplifies a bad value. Applied manually to the dev DB
-- (Flyway not enabled; db/migration is reference + replay source for
-- schema-init, regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('element.survey.tail-cap', '400', '系统', 'int', 0, '「测绘进行中」过程窗保留的事件条数上限（默认 400；可设 50~5000，保存即生效；超出后最早的过程事件不再显示，入账结果不受影响）'),
    ('ui.tail.heartbeat-seconds', '15', '系统', 'int', 0, '过程详情静默多少秒后显示一条「仍在进行」提示（默认 15；可设 2~300，保存即生效；用于确认连接仍在工作，不是新的执行动作）'),
    ('ui.tail.first-frame-ms', '2000', '系统', 'int', 0, '打开过程详情后首条事件的最长等待毫秒数（默认 2000；可设 500~60000，保存即生效；超时会显示等待说明而不是空白面板）')
ON CONFLICT (config_key) DO NOTHING;
