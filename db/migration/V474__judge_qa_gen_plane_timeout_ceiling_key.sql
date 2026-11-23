-- Migration: question-generation plane LLM per-call timeout ceiling .
--
-- Why this file exists:
--   Document 9016's grouped qa-gen run rode the global 300s provider window;
--   one wedged upstream attempt burned 300s x 3 retries = 900s on a single
--   group, while the measured healthy single-group call needs only 25~60s.
--   The judge plane now composes its OWN ceiling under the caller clamp (same
--   shape as the extraction plane, one shared seam in LlmTimeoutResolver),
--   read hot per call through LlmTimeoutResolver.resolveJudgeQaGen.
--   Default derivation: 120s = 2x the observed healthy upper bound (60s), so
--   healthy runs stay byte-identical while a wedged provider costs at most
--   120s x 3 retries = 360s per group instead of 900s.
--   Value semantics: blank = follow the global llm.timeout-seconds face (zero
--   behaviour change); otherwise a whole-second value inside the same 10~300
--   window the global key pins. The write side rejects out-of-window and
--   garbage values with a plain 400 naming the bounds; the reader falls back
--   to the global face with a warn, never silently off.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.judge.qa-gen.llm-timeout-seconds', '120', '系统', 'string', 0, '出题生成链单次调用的超时上限（秒）。留空=沿用全局 llm.timeout-seconds；填写则只收紧出题生成链（分组造题）的单次调用。允许 10~300；超界或不合规会被拒绝并提示原因。保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
