-- AX batch review-flag threshold for extracted requirement points.
-- The extraction preview shows a per-item confidence the model reported, but a
-- low-confidence item was rendered exactly like a high-confidence one, so the
-- reviewer had no cue to double-check it before the confirm-into-library gate.
-- This key carries the threshold: a preview item whose confidence is below it
-- is flagged for manual review (chip + jump-back anchor), and the preview API
-- echoes the threshold so the frontend never hardcodes it.
-- * hot-updatable like the rest of the ai.doc.* group (write-side range check
--   lives in the core config validation, single source shared with the seed).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.review-flag-threshold', '0.7', '系统', 'string', 0,
     '需求点提取预览的待核对阈值（默认 0.7，允许 0.1~1；模型置信度低于该值的需求点在预览中标记为待核对，建议点开原文锚点人工比对后再勾选入库；改值刷新预览即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
