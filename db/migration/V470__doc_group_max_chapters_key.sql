-- Migration: chapter-span cap for the document-extraction grouping plane
-- .
--
-- Why this file exists:
--   Chapter-aligned grouping seals a group where the chapter changes, but a
--   document with many SHORT chapters could still weld dozens of them into
--   one node while the token/chunk ceilings stay below reach — one prompt
--   then spans a whole document section and its summary node labels stop
--   meaning anything. This key caps how many DISTINCT chapters one sealed
--   group may carry: the group closes the moment adding a chunk from a NEW
--   chapter would push the count past the cap, and a single oversized
--   chapter is still bounded by the token/chunk ceilings (the cap never
--   splits inside one chapter). Write side validates the same window; the
--   reader fails closed to the factory value.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.group-max-chapters', '20', '系统', 'string', 0, '单个提取节点跨越的最大章节数（默认 20，允许 1~1000；短章节过多时节点在集满该章数处封口，单个超长章节仍按内容量与块数上限拆分。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
