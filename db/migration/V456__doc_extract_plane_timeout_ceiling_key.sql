-- Migration: extraction-plane LLM per-call timeout ceiling .
--
-- Why this file exists:
--   Document 8491 burned the 300s global provider window inside final-extract
--   rounds while the same budget was far too generous for a single-plane call
--   stall. The extraction plane now composes its OWN ceiling under the task
--   deadline clamp (min(window remaining, this ceiling, provider timeout)),
--   read hot per call through LlmTimeoutResolver.resolveDocExtract.
--   Value semantics: blank = follow the global llm.timeout-seconds face
--   (zero behaviour change); otherwise a whole-second value inside the same
--   10~300 window the global key pins. The write side rejects out-of-window
--   and garbage values with a plain 400 naming the bounds; the reader falls
--   back to the global face with a warn, never silently off.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.llm-timeout-seconds', '', '系统', 'string', 0, '需求点提取链单次调用的超时上限（秒）。留空=沿用全局 llm.timeout-seconds；填写则只收紧提取链（最终提取/分组摘要/缺口追问）的单次调用，与任务总时限取更小值。允许 10~300；超界或不合规会被拒绝并提示原因。保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
