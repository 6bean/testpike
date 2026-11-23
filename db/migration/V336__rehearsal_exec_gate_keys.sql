-- Migration: K8 config keys for the three-facet rehearsal launch gate .
--
-- Why this file exists:
--   The conversation can now start a confirmed regression-rehearsal batch, so
--   the launch path gains two new settings next to the existing dialog
--   execution master switch (ai.chat.exec-enabled, reused, not re-seeded):
--   a per-application-point sub switch and a project whitelist, ANDed coarse
--   to fine by RehearsalExecGate.
--   ai.chat.rehearsal-run.enabled ships TRUE: it is an AI feature switch and
--   the default-open rule enables those out of the box. Safety is enforced
--   independently of the default value — a project must still appear in the
--   id whitelist and every launch needs a manual confirmation click. An off
--   value never silences the answer — the refusal names this key and the
--   settings path (guidance contract).
--   ai.chat.exec-project-whitelist is a comma-separated list of project ids
--   allowed to launch execution-class batches from the dialog; the default is
--   empty, i.e. no project is allowed until an admin names one (fail-closed).
--   Readers fall back to both defaults when a row is absent, so behaviour on
--   old databases is identical. The write side guards the bool face through
--   AiChatKeyRanges (K8 convention: no DB CHECK, service layer is the range
--   source). Descriptions below carry only operational facts: default value,
--   effect, where to change.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.exec-project-whitelist', '', '系统', 'string', 0, '允许从对话启动执行批次的项目编号列表（英文逗号分隔，默认为空=任何项目都不允许；修改后立即生效，在「系统设置」中修改）'),
    ('ai.chat.rehearsal-run.enabled', 'true', '系统', 'bool', 0, '对话启动回归演练批次的开关（默认关闭；开启后仍需本项目在编号列表内，且每次启动都要人工点击确认才会执行；在「系统设置」中修改）')
ON CONFLICT (config_key) DO NOTHING;
