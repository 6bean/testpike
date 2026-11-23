-- Measured-confidence switch : after the stage1 ring
-- converges, the persisted confidence replaces the model self-report with a
-- measurement built from signals the system observed this run — faithfulness
-- ratio, acceptance-criteria coverage and source-anchored share of assertive
-- claims. Absent signals leave the mean; when nothing could be measured the
-- self-report is kept (never a fabricated number). A measured "low" auto-limits
-- the depth: the script stage is skipped and the notification says honestly
-- that no script was produced — the case itself still lands on the normal
-- human review doors, and the manual script regeneration path stays untouched.
-- The row is read per generation (hot update, no restart). The switch ships
-- on — a generation run never needs setup to be measured for confidence.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.confidence-signals.enabled', 'true', '系统', 'bool', 0,
     '生成置信度实测开关（默认开启；用例置信度改由忠实核对、验收覆盖、原文出处三项实测取值，三项缺哪项就按其余两项平均，全部无法实测时保留模型自报；实测为低时不再生成脚本并在通知中如实说明，可重新生成；关闭后置信度维持模型自报，其余流程不变；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
