-- V555__script_writeback_reproof_ttl_seconds.sql — ledger window key for the
-- platform re-validation of landed off-site script write-backs.
-- Seeds script.writeback.reproof.ttl-seconds (factory default 86400 = 24 hours):
-- the lifetime of the one-shot Redis ledger entry script:reproof:{taskId} that
-- links a write-back landing to its platform trial-run task. When the trial
-- settles (pass or fail) the entry is consumed immediately; when the task never
-- settles the entry lapses with this TTL, so no stale ledger accumulates
-- (automatic end-of-life, R57 lifecycle bound at the creation point). The value
-- is hot-updatable through tp_sys_config and read live on every scheduling;
-- illegal or non-positive values fall back to the shipped default.
-- The shipped default stays in sync with DEFAULT_TTL_SECONDS on
-- ScriptWritebackPlatformReproof (drift between them is a defect).
-- Idempotent replay.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('script.writeback.reproof.ttl-seconds', '86400', '系统', 'int', 0, '脚本写回后平台试运行复证台账的有效期秒数（默认 86400，即 24 小时；试运行出结果即销账，任务长时间未出结果时台账到期自动失效，重新发起写回即可触发新的复证）')
ON CONFLICT (config_key) DO NOTHING;
