-- Migration: chapter-aligned grouping switch for the document-extraction
-- summarize plane .
--
-- Why this file exists:
--   F5 grouping was a pure budget greedy, so one sealed group could weld
--   chunks of several chapters together and the group's summary node was
--   labelled only by its FIRST chunk's chapter — downstream attribution
--   collapsed ("which chapters actually ride in this node?" became
--   unanswerable). This key routes the SAME greedy loop through a chapter
--   boundary face: a group seals where the chapter changes, while budget and
--   node chunk caps still bound one oversized chapter, and the overlap tail
--   keeps riding across the seam. Factory ON; storing "false" restores the
--   legacy budget-only face byte-identically (write side validates the bool
--   form, the reader fails closed to ON). The final-bucket plane is untouched.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.chapter-aligned-grouping.enabled', 'true', '系统', 'bool', 0, '长文档分组摘要按章节边界分组的开关（默认开启；分组在章节变化处封口，单个章节超出分组预算时仍按预算拆分。改为 false 保存即生效，无需重启，恢复仅按预算分组的旧形态）')
ON CONFLICT (config_key) DO NOTHING;
