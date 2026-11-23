-- Migration: evidence-anchor landing gate switch for the requirement-point
-- extraction plane .
--
-- Why this file exists:
--   Live incident: document 8639 landed 50 requirement points whose evidence
--   anchors were all empty, so the case-generation plane could never inject
--   the original text back ("fetched blocks > 0, injected = 0"). The
--   extraction plane now attributes every item to its source block
--   server-side; this switch decides what happens to the residue that still
--   cannot be attributed. Factory "true": a requirement point whose evidence
--   excerpt cannot be traced back to a source block is held back from the
--   write and the held-back count is reported in the extraction process
--   stream. Setting "false" restores the previous write-everything posture.
--   Read hot on every run — saving the value takes effect immediately, no
--   restart needed.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.anchor-required.enabled', 'true', '系统', 'string', 0, '证据锚齐全开关（默认开）。开启时，证据摘录回指不到原文块的需求点不会入库，拦截条数会在提取过程详情中点名；关闭后所有需求点照常入库。保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
