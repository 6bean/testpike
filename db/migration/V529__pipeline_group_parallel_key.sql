-- Migration: seed row for the group-level parallel fan-out key .
--
-- Why this file exists:
--   The three extraction call-sites (main-judgement group loop, spec-contract
--   extraction, background question generation) now run their per-group LLM
--   calls through one shared bounded fan-out component. The component reads
--   this key live from tp_sys_config on every round, so the parallel degree is
--   adjustable without a restart. Without a seed row the key would be absent
--   from the configuration page even though the code honours it, which breaks
--   the key-three-sources convention every other hot key follows.
--   Cap semantics: factory value equals the hard cap (3). Values above the cap
--   are clamped down at read time, never rejected — raising the cap itself is a
--   separate adjudicated change, not a configuration edit. A missing or blank
--   row falls back to the factory value (fail-closed).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.pipeline.group-parallel', '3', '系统', 'string', 0, '文档与生成任务分组并行调用数（默认 3，允许 1~3；提取、契约生成、出题三类任务的每组大模型调用同时发起的数量上限，设为 1 表示逐组串行、与并行改造前的行为完全一致。超过 3 的取值会被自动压回 3。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
