-- Deadline knob for the AI backfill round of the import-suggestion preview:
-- rows wait for refinement only this long, past it the deterministic advice
-- is served under its honest rule-default label. The consumer
-- (SpecImportVerdictServiceImpl advice-state band) reads this key fresh on
-- every preview, so changes take effect without a restart.
--
-- Flyway is not wired in this project: apply manually to the target database,
-- then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('mock.spec-import.verdict-deadline-seconds', '20', '接口仿真', 'int', 0,
     '批量导入预览等待智能建议返回的时限秒数（默认 20；超过时限未返回时按内置规则给出建议并标注为规则默认；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
