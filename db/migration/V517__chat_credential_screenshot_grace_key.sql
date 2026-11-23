-- Migration: piece E — grace window for the credential-screenshot compensating sweep.
-- A DevTools Application-panel screenshot pasted into the dialog is a credential carrier:
-- the inline terminal-state delete purges the original image right away; this key bounds
-- how long a booked-but-undeleted frame (e.g. a crash between ledger mark and delete)
-- waits before the periodic sweep deletes it by anchor. Read side falls back to the
-- default below when the key is absent or unparsable; write side (core ConfigServiceImpl
-- via AiChatKeyRanges) enforces the 1~1440 interval. Applied manually to the dev DB
-- (Flyway not enabled; db/migration is reference + replay source for schema-init,
-- regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.credential-grace-minutes', '10', '系统', 'int', 0, '浏览器登录态截图在解析完成后保留原图的分钟数（默认 10；可设 1~1440，热更即生效）。到期后系统按台账自动删除原图，历史记录与分享面此后只显示说明行，不再显示截图内容。')
ON CONFLICT (config_key) DO NOTHING;
