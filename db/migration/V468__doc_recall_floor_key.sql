-- Migration: recall floor for the golden-set recall regression check
-- .
--
-- Why this file exists:
--   The live incident (document 8639) showed the extraction plane can reach
--   a "green" preview while only a handful of the source requirements made
--   it into the result. The golden-set fixture (deterministic desensitized
--   copy at the same structure scale, paired with its annotated item set)
--   runs the real chain and asserts every run recovers at least this ratio
--   of the annotated requirement points, with chapter coverage >= 90% of
--   annotated chapters and 100% non-empty evidence anchors. Factory 0.85;
--   the write side validates the 0~1 window with a plain 400 (same shape as
--   ai.doc.fidelity-retention-floor), the spectrum reader fails closed to
--   the factory value and names the key.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.recall-floor', '0.85', '系统', 'string', 0, '需求点提取结果对标注清单的召回率下限（默认 0.85，允许 0~1；定期回归检查中召回率低于该下限时判定为不达标并提示原因。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
