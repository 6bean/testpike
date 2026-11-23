-- Migration: grouping overlap window ratio for the document-extraction planner .
--
-- Why this file exists:
--   Greedy grouping sealed a group exactly at the budget edge, so a requirement
--   point written across the seam could be torn in half and missed by both
--   neighbouring summarisation rounds. This key sizes the shared boundary
--   window: adjacent groups re-carry the tail chunks of the sealed group whose
--   cumulative size stays within ratio x budget, charged into the next group's
--   budget (never over-issued). Chapter bucketing of the final extraction call
--   consumes the same overlap function later in this batch. Write side rejects
--   values outside 0~0.25 with a plain 400; the reader fails closed to the
--   factory value and names the key in the log. Setting 0 restores the exact
--   legacy grouping behaviour (zero drift).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.group-overlap-ratio', '0.10', '系统', 'string', 0, '文档提取封组重叠窗口比例（默认 0.10，允许 0~0.25；相邻分组共享边界内容的比例，重叠部分计入下一组预算。设为 0 表示关闭重叠、与旧分组行为完全一致。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
