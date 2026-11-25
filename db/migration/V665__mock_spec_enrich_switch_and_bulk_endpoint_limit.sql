-- Factory defaults for the contract-bulk enrichment drafting point:
--   * ai.mock.spec-enrich — per-application-point sub switch, shipped ON so a
--     fresh install can start bulk enriching drafted stubs with no setup; the
--     master gate ai.mock.enabled is ANDed with it at submit time.
--   * mock.spec-import.bulk-endpoint-limit — how many endpoints ONE bulk
--     enrichment task may cover (default 200). A contract version row with
--     more endpoints is refused at submission, naming the real count and the
--     cap, so the operator splits by resource path instead of burning a
--     partially-failed run. Hot key: the service reads it on every submit.
--
-- Flyway is not wired in this project: apply manually to the target database,
-- then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.mock.spec-enrich', '1', 'AI 配置', 'bool', 0,
     '「AI 数据完善」应用点开关（默认开；关闭后发起该类任务会被拒绝并提示打开入口）'),
    ('mock.spec-import.bulk-endpoint-limit', '200', '接口仿真', 'int', 0,
     '一次批量完善任务最多覆盖的接口数（默认 200；超过时发起会被拒绝并提示分批处理；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;

-- The job-type column carries a DB-level CHECK enum (V229). A new drafting
-- point is only truly accepted once the constraint knows it: widen the enum
-- here, idempotently, so re-running this migration never fails.
ALTER TABLE tp_mock_ai_job DROP CONSTRAINT IF EXISTS ck_mock_ai_job_type;
ALTER TABLE tp_mock_ai_job ADD CONSTRAINT ck_mock_ai_job_type CHECK (
    job_type IN ('nl-stub', 'edge-scenarios', 'llm-fallback',
                 'record-summarize', 'spec-sync', 'spec-enrich')
);
