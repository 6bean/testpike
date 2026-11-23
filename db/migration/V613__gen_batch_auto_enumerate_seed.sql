-- Batch default full-enumeration switch (scenario circle-selection default door):
-- the stage0 scenario enumeration already exists (V421 keys + preview door),
-- but submitting a batch WITHOUT running the preview first silently produced one
-- case per requirement line — the least complete shape. This key flips the
-- default: when on, a preview-less submit first auto-enumerates the five scenario
-- kinds (main flow / equivalence class / counter example / boundary / state
-- combination), starts fully selected, and submits expanded (one requirement
-- point becomes several cases). OFF = the legacy whole-row shape, zero drift.
-- The circle-selection panel and the one-click cost confirmation stay human
-- gates outside this key. Read per call (hot update, no restart needed).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.batch.auto-enumerate.enabled', 'true', '系统', 'bool', 0, '批量生成默认自动枚举开关（默认开：不先点「场景预览圈选」直接提交时，系统先自动枚举五型场景（主流程、等价类、反例、边界、状态组合）并默认全选，一个需求点生成多条用例；关闭=恢复按整行一条用例的旧行为；修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
