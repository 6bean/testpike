-- Migration: governance ceiling for the chat composer height drag (,
-- adjudication §two.5). The rows a user drags the composer open is a
-- personal preference on localStorage; this key caps how far it can grow —
-- an admin lowers it (e.g. kiosk displays), the composer clamps live and a
-- stored oversized value re-folds on the next read. Reader fails soft to 10
-- when the row is missing or unparsable; the write side interval-guards
-- 3~20 (below the default height 3 the cap would squeeze the box smaller
-- than its own factory rows).
-- Usage: docker exec -i testpike-postgres psql -U testpike -d <slot-db> -f - < db/migration/V508__chat_composer_max_rows_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.composer-max-rows', '10', '系统', 'int', 0, '对话输入框可拖拽的最大行数（默认 10，允许 3~20）。调小后输入框拖拽高度随之封顶，已保存的超高值下次打开自动收敛。在「系统设置 → 配置管理」修改，保存即生效，无需重启；超出允许区间会被拒绝并提示原因')
ON CONFLICT (config_key) DO NOTHING;
