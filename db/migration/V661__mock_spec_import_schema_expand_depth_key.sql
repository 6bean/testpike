-- Contract-direct stub drafting reads the stored endpoint snapshot and never
-- re-runs the parser, so response schema expansion needs one tunable limit:
-- how deep a declared schema unfolds into the sample body before an honest
-- truncation note is left instead of guessing. Cycles in referenced models
-- always stop with a $cycle marker regardless of this value. The row is read
-- per preview request (hot update, no restart).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('mock.spec-import.schema-expand-max-depth', '6', '接口仿真', 'int', 0,
     '契约批量生成 Mock 时响应结构展开的最大层级（默认 6；层级或大小超上限的部分会以 truncation 标记如实呈现，不会凭空造数据；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
