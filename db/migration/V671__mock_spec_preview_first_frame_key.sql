-- First-frame budget for the contract-bulk preview round (MJ P7): a very
-- large OpenAPI contract is still a deterministic synchronous render, but the
-- wizard must not keep the operator in front of a bare spinner forever — past
-- this many milliseconds one honest waiting line appears (the same
-- first-response degradation posture as the process tail window). The wizard
-- reads the row fresh per page load and falls back to the factory default
-- without the system-config permission (fail-soft, no restart needed).
--
-- Flyway is not wired in this project: apply manually to the target database,
-- then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('mock.spec-import.preview-first-frame-ms', '1500', '接口仿真', 'int', 0,
     '契约批量生成预览等待多久后显示进度提示（默认 1500 毫秒；预览始终先按契约内容即时生成，提示只说明仍在处理中；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
