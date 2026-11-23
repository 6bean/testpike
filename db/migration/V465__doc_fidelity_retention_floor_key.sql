-- Migration: fidelity retention floor for the requirement-point extraction
-- plane .
--
-- Why this file exists:
--   The legacy Map-Reduce shape fed the extract stage GROUP SUMMARIES, so a
--   121k-token document reached the final extraction with only ~4% of its
--   source tokens injected (live incident: document 8639, 50 items with empty
--   evidence anchors). The batch replaces that with per-group ORIGINAL-TEXT
--   extraction; this key is the acceptance yard of the shape — the ratio of
--   injected source tokens to whole-document tokens must stay at or above the
-- floor, and the fidelity gate judges each run against it.
--   Factory 0.95; the write side validates the 0~1 window with a plain 400,
--   the reader fails closed to the factory value and names the key in the log.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.fidelity-retention-floor', '0.95', '系统', 'string', 0, '需求点提取注入原文占正文的比例下限（默认 0.95，允许 0~1；提取结果的原文注入比例低于该下限时会被判定为保真度不达标并提示原因。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
