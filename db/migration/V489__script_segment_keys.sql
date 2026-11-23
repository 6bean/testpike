-- V489__script_segment_keys.sql — seed the three ai.script.* segment keys .
-- Purpose: step-group slicing of stage-2 script generation needs hot-updatable
-- knobs (width / per-slice model-request timeout / per-slice retry). Values are
-- read fresh per generation task (K8 semantics: a change takes effect on the
-- next run). Range enforcement lives in the service layer (AiScriptKeyRanges);
-- the DB carries no CHECK, same as the rest of this key family.
-- slice-timeout-seconds keeps the >=800s per-hop floor for model requests and
-- is registered in the timeout-floor detector alongside it.
-- Idempotent: ON CONFLICT DO NOTHING (replay-safe).
-- Usage: docker exec -i testpike-postgres psql -U "$DB_USERNAME" -d testpike < db/migration/V489__script_segment_keys.sql

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.script.slice-steps',          '10',  '系统', 'int', 0, '步骤组拆片宽度：每次模型请求生成的步骤数（默认10，区间0~100；0=不拆片，整份用例一次生成；参数改动下次生成即生效）'),
('ai.script.slice-timeout-seconds','900', '系统', 'int', 0, '单个步骤组模型请求超时秒（默认900，区间800~3600；超时按该组失败处理）'),
('ai.script.slice-retry',          '1',   '系统', 'int', 0, '单个步骤组失败后的重试次数（默认1，区间0~3；0=不重试）')
ON CONFLICT (config_key) DO NOTHING;
