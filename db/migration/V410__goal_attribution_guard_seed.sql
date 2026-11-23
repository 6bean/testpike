-- Goal-lifecycle attribution guard (behaviour kernel second round, item 1):
-- every final dialog answer self-reports whether the round continues the
-- session's open goal or opens a new one. The autonomous continuation loop
-- closes the old ledger with a stored trace (unfinished steps end there and
-- never flow into the new answer); an answer without any declaration loads
-- nothing — the pinned fail-safe direction: losing continuity is preferred
-- over letting a stale goal pollute an unrelated answer. The row is read
-- hot per turn — changes land on the next dialog turn, no restart. With the
-- switch closed the dialog keeps the exact previous behaviour (open goals
-- auto-carry without requiring the declaration line).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.goal-attribution.enabled', 'true', '系统', 'bool', 0, '目标归属自报开关（默认开）：开启时，每轮回答会自报是在继续未完成的目标还是开始新目标；只有自报“继续”才会自动接续未完成目标的内容，自报“开始新目标”会把旧目标的未完成步骤结束并留痕，未自报则不自动接续（可发送「继续」手动恢复）。关闭后恢复原行为：未完成的目标每轮自动接续，无需自报。修改后下一轮对话即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
