-- Stall-reflection streak bar (behavior kernel item 8): how many
-- consecutive same-fingerprint failures of one tool+arguments shape the ReAct
-- loop tolerates before it refuses the next identical call and hands the
-- model a strategy-change frame (variant query / clarify the user / honest
-- residual answer). Read once per run — hot update lands on the next run,
-- no restart. Ships enabled with the industry-standard bar of 2: the guard
-- only fires after two REAL repeated failures of the exact same call, so
-- honest traffic is never touched.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.behavior.stall-block-attempts', '2', '系统', 'int', 0, '停滞反思阈值：同一工具以完全相同参数连续同类失败达到该次数后，下一次相同调用会被系统拒绝，并提示换一种获取方式、向用户追问或如实说明数据暂不可得（默认 2；正整数；修改后下一个任务生效）')
ON CONFLICT (config_key) DO NOTHING;
