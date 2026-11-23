-- Migration V630: stall window for zombie "generating" AI tasks.
--
-- Why this file exists:
--   A generation task rides tp_ai_task.status=2 from dispatch until ai-service
--   writes the outcome back. When the consumer dies mid-flight (service
--   restart, message loss, killed LLM call) nothing reclaims the row: the live
--   audit found 12 rows stuck beyond five hours (oldest 9 days) while the
--   queue panel still said "generating" and the backlog gauge overstated.
--   The new sweep (aiTaskRunningStallSweep, every 2 minutes) marks status=2
--   rows whose updated_at passed this window as failed through the existing
--   restarts it. Operators can widen/shrink the window hot from the config
--   page; garbage or missing values fall back to 60 in the reader (never off,
--   never zero, never fail-everything).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.task.running-stall-minutes', '60', '系统', 'int', 0, '生成任务停滞判定分钟数（默认 60；「生成中」超过该时长无回写即判定中断并标记为失败，在「AI 生成」页右侧「生成任务进度」面板对该行点「重新生成」即可再次发起；热更即生效，非法值按 60 回退，非正数拒绝清扫）')
ON CONFLICT (config_key) DO NOTHING;
