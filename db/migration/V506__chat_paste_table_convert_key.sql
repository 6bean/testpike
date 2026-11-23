-- Migration: hot-updatable switch for the chat composer paste enhancement
-- (clipboard tables converted to GitHub-flavored Markdown pipe tables at the
-- caret). The reader side (frontend chatPasteEnhance composable) loads this
-- key once after login and fails soft to ON when the row is missing or the
-- read fails, so the feature works out of the box with zero configuration;
-- with the key off the paste stays verbatim and the composer points at this
-- page once. Ships TRUE per the AI-feature default-on rule.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d <slot-db> -f - < db/migration/V506__chat_paste_table_convert_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.paste-table-convert.enabled', 'true', '系统', 'bool', 0, '对话粘贴表格转 Markdown 开关（默认开启；粘贴电子表格或网页表格时自动转为管道表格文本插入光标处，关闭后按原样粘贴不转换。在「系统设置 → 配置管理」修改，保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
