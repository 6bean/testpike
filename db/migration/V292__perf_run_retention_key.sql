-- Migration: retention window key for finished load-test runs (run-history retention).
--
-- Why this file exists:
--   The daily retention sweep now carries a fifth data class: perf runs that have
--   reached a terminal state and are older than this window are removed together
--   with their per-second metric and event-sample child rows. Golden-marked runs
--   are exempt forever (they are the trend/baseline reference points).
--   The window itself lives in perf.run.retention-days so an operator can widen or
--   shrink history retention without a release; an invalid or missing row falls
--   back to 90 days in the reader (never off, never zero).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('perf.run.retention-days', '90', '系统', 'int', 0, '压测运行历史保留天数（默认 90；到期后结束的非金跑连同逐秒指标一并清理，标记为金跑的运行永久保留；热更即生效，非法值按 90 回退）')
ON CONFLICT (config_key) DO NOTHING;
