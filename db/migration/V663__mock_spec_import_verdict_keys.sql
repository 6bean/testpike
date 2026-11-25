-- Retention knob for the import-suggestion table: rows are advisory previews,
-- they age out on their own. The cleaner (SpecImportVerdictRetentionCleaner)
-- reads this key fresh on every round, so the limit changes take effect without
-- a restart. The companion deadline knob lands together with its own consumer
-- (the asynchronous AI backfill round), not before it.
--
-- Flyway is not wired in this project: apply manually to the target database,
-- then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('mock.spec-import.verdict-retention-days', '90', '接口仿真', 'int', 0,
     '仿真契约批量导入的智能建议保留天数（默认 90；到期行自动清理，不影响已入库规则；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
