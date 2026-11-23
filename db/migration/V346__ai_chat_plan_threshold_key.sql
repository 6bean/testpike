-- Migration: hot-update config key for the multi-step plan card (AO plan-card segment).
--
-- Why this file exists:
--   A long chain (many tool calls) must show its step plan before running, while
--   a one-round question must not be interrupted by a card. Where that line sits
--   is an operator preference, so it is a config key, not a constant: the dialog
--   worker compares its estimated tool-call count against this value and emits a
--   'plan' frame only when it reaches the threshold. The write side guards the
--   interval in AiChatKeyRanges (K8 convention: no DB CHECK, the service layer
--   table is the single source); readers fall back to the seeded default on a
--   missing or unparsable value (fail-closed).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.plan-threshold', '3', '系统', 'int', 0, 'AI 多步计划卡的触发门槛（默认 3；区间 2~10，热更即生效；预计执行步数达到该值的任务会先出计划卡等待确认，低于该值的任务直接执行不出卡）')
ON CONFLICT (config_key) DO NOTHING;
