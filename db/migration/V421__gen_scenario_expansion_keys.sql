-- Stage0 scenario enumeration keys (BA batch ruling section 3 item 3):
-- generation becomes a constrained fill-in — the source text is first
-- expanded into a scenario list (equivalence classes / counter examples /
-- boundaries / state combinations), the user circle-selects the scenarios,
-- then one case is produced per selected scenario.
--   * switch OFF = the legacy one-line-one-case behaviour, zero drift;
--   * cap trims the list per requirement point, range 1..20;
-- both rows are read per call (hot update, no restart). The selection and
-- the one-click cost confirmation stay human gates outside these keys.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.scenario-expand.enabled', 'true', '系统', 'bool', 0, '场景枚举开关（默认开：生成用例前先按需求点原文扩出等价类、反例、边界、状态组合的场景清单供圈选，逐场景产用例；关闭=恢复一条需求产一条用例的旧行为；修改即时生效，无需重启）'),
    ('ai.gen.scenario-max-per-requirement', '8', '系统', 'int', 0, '单个需求点的场景数量上限（默认 8；区间 1~20；超出上限的场景会被截断不进圈选清单；修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
