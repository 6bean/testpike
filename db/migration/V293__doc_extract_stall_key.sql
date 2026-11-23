-- Migration: stall window for zombie "extracting" documents .
--
-- Why this file exists:
--   An F2 extraction rides tp_document.status=3 from dispatch until ai-service
--   writes back. When the consumer dies mid-flight (restart, message loss,
--   killed LLM call) nothing ever reclaims the row: the document stays stuck
--   on "extracting" and the state machine refuses a re-dispatch. The new sweep
--   (docExtractStallSweep) moves status=3 rows whose updated_at passed this
--   window back to 1 with a human-readable extract_error so the user can retry.
--   The window is 2x the ai-service consume lease (15min) by construction —
--   a healthy run always touches or finishes inside the first lease. Operators
--   can widen/shrink it hot via the config page; garbage or missing values fall
--   back to 30 in the reader (never off, never zero, never reset-everything).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.extract-stall-minutes', '30', '系统', 'int', 0, '文档提取停滞判定分钟数（默认 30=消费租期 15 分钟的两倍；提取中超过该时长无回写即判定中断并恢复为可重新提取状态，再次发起提取即可；热更即生效，非法值按 30 回退，非正数拒绝清扫）')
ON CONFLICT (config_key) DO NOTHING;
