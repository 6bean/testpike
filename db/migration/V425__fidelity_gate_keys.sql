-- Faithfulness gate keys :
-- after stage1 drafts a case, the FidelityGate re-verifies its key claims
-- (quantities, boundary/state/negation wording) against the original source
-- blocks. Unsupported claims become named feedback and drive one targeted
-- regeneration; once the round budget is spent the leftovers are announced in
-- the task log instead of being dropped, and the draft still follows the
-- normal human confirmation doors. Both rows are read per generation (hot
-- update, no restart). The master switch ships on — a generation run never
-- needs setup to be checked for faithfulness.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.fidelity-gate.enabled', 'true', '系统', 'bool', 0,
     '生成忠实核对开关（默认开启；生成的用例步骤会逐条核对数值与边界、状态、否定措辞是否来自需求原文，不来自原文的会被点名并要求改写；关闭后跳过核对，其余流程不变；修改即时生效）'),
    ('ai.gen.fidelity-recheck-rounds', '1', '系统', 'int', 0,
     '忠实不一致重生成轮数（默认 1；区间 0~2；0=只点名不重生成；超轮数仍未消除的不一致会在任务日志中点名亮出；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
