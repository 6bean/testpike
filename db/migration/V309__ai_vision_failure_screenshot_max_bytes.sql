-- Failure-scene screenshot size cap (AO-8 screenshot multimodal, stage-2 fix).
--
-- Why this key exists:
--   The dialog screenshot read inlines the artifact bytes as a base64 data url
--   because cloud vision providers cannot fetch the deployment's own storage
--   host server-side (Dashscope rejected the presigned localhost url with 400,
-- live-proven ). Inlining bounds the prompt payload, so the accepted
--   screenshot size needs an operator-tunable cap instead of a hardcoded one —
--   per the platform-wide rule that every threshold is hot-tunable in system
--   settings (read per call, no caching).
--
-- Value shape: int, bytes; default 8388608 (8 MB) matching the design cap;
-- non-positive/illegal values fall back to the default at the read point
-- (SysConfigInts.positiveInt single-sourced parsing).
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.vision.failure-screenshot-max-bytes', '8388608', '系统', 'int', 0,
     '对话失败现场截图读图的单图大小上限（字节）：超过该值的截图会被放弃并说明原因，回答仍基于失败详情文本；默认 8388608（8 MB），调大后下次读图即生效')
ON CONFLICT (config_key) DO NOTHING;
