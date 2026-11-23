-- AG batch (M10 : newest-frame cap for the A-4 induction sample.
--
-- Why this file exists:
--   The record-summarize leg (ai-service MockDraftGenServiceImpl) pulls a
--   bounded sample of a sealed box's masked frames before spending any token.
--   The cap must be an operator-visible, hot-tunable K8 row rather than a
--   code-only default: long cassettes (hundreds of frames) would otherwise
--   blow the prompt budget of the pinned 4000-token QUOTA stamped at create
--   time (V239/V241 posture). Core trims to the NEWEST N frames (capture
--   order tail) because recent traffic best represents the current contract;
--   reading stays honest either way — open box = live hold, sealed = cassette.
--   Values < 1 are clamped to 1 at read time (an empty sample would only
--   produce a failed job, so a nonsense key never silences induction).
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('mock.ai.summarize.max-sample-frames', '50', 'AI 配置', 'int', 0,
     'A-4 录制归纳单次采样的最新帧数上限：封盒流量再多也只取最新 N 帧进 prompt（帧均已脱敏）；热更即时生效，下一次归纳任务读取；<1 按 1 收拢，缺行按 50 回退')
ON CONFLICT (config_key) DO NOTHING;
