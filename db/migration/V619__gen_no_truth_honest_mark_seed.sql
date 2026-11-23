-- Unevidenced-value marking switch for AI case generation:
-- when a generated step carries a URL, an element locator or an expected
-- literal that no probe record, action contract or ledger entry backs up,
-- the stage-2 prompt discipline requires the draft to mark that value as
-- and the fidelity gate flags drafts that skip the mark. This key controls
-- the whole behavior. ON (factory default) = the mark discipline applies;
-- OFF = the legacy shape with zero drift (no extra prompt clause, gate keeps
-- its previous detection surface only). Read per call (hot update, no restart).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.no-truth-honest-mark.enabled', 'true', '系统', 'bool', 0, '生成无据值标注开关（默认开：步骤里的地址、元素定位或期望值没有探查记录或契约依据时，标注「需人工精化」并计入置信判断；关闭=保持旧生成行为；修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
