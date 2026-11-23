-- Migration: task-level time budget for F2 document extraction .
--
-- Why this file exists:
--   A dispatched extraction has no wall-clock cap of its own: when a provider
--   stalls, the fallback chain + backoff retries + per-group summarization can
--   keep one document alive for hours, burning tokens with no write-back. The
--   extraction run now anchors a deadline from this key at entry and clamps
--   every LLM call's timeout to the remaining window; once the window is spent
--   the run fails fast through the normal write-back path (status 3 -> 1 with a
--   human-readable error), so a plain retry recovers the document. Garbage or
--   missing values fall back to 900 in the reader (the guard is never silently
--   off). 900s comfortably covers the observed worst case: a healthy multi-
--   round summarization run finishes inside one consume lease (15min = 900s),
--   matching the stall sweep calibration (ai.doc.extract-stall-minutes = 2x).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.extract-deadline-seconds', '900', '系统', 'int', 0, '文档提取任务级时长预算秒数（默认 900=消费租期 15 分钟对齐；提取全程超出该预算即中止并恢复为可重新提取状态，再次发起提取即可；单次模型调用超时按剩余时间自动缩短；热更即生效，非法值按 900 回退）')
ON CONFLICT (config_key) DO NOTHING;
