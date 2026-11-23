-- Session-history date-group buckets for the chat session list.
--
-- Why this key exists:
--   The dialog history panel groups conversations by date (today / within the
--   last 7 days / within the last 30 days / earlier). The boundaries are
--   operator-tunable: a comma-separated ASCENDING list of day thresholds.
--   Value shape: value_type='string' because a comma list is not an int
--   (tp_sys_config value-type set = int/bool/string/json, string-precedent
--   for non-int shapes). The admin group-days door parses the value and falls
--   back to 1,7,30 on any malformed shape, so a typo never breaks grouping.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.session-group-days', '1,7,30', '系统', 'string', 0,
     '历史会话列表的日期分组边界天数，从小到大用逗号分隔（默认 1,7,30，即分为今天、近7天、近30天、更早四组；热更即生效；格式不合法时列表按默认边界分组）')
ON CONFLICT (config_key) DO NOTHING;
