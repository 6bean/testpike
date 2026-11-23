-- Migration: hot switch for the exhaustive-recall clause block on the
-- requirement-extraction prompt face .
--
-- Why this file exists:
--   Enumeration tasks without a recall instruction settle at the provider's
--   summarising habit (about 5-9 items regardless of source density) and the
--   group-level caches pin that number across reruns. The clause block now
--   rides the extraction system prompt; this key lets an operator turn the
--   recall section off without a redeploy.
-- Behaviour notes:
--   * the reader is fail-soft: a missing, blank or illegal value stays ON
--     (AI features ship enabled), only false/0 turn it off — this seed only
--     makes the knob visible and editable on the configuration page (it is
--     not a second default-value source);
--   * flipping the switch changes the prompt text, and the prompt-face tag
--     mixed into the group cache key re-hashes with it: cached groups are
--     recomputed instead of reusing items pinned under the old wording.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.recall-directive.enabled', 'true', '系统', 'bool', 0, '需求点提取的穷尽召回指令开关（默认开启；开启后提取提示要求逐块挖掘每一条可测试行为、不把独立行为合并成要点、条数不设上限、无需求的块显式声明空块。值缺失或不合规时按开启处理并在日志点名本键。改为 false 后提示恢复旧口径；两种口径之间切换时已缓存的分组会随提示文本变化自动重算，不会沿用旧提示算出的结果。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
