-- Migration: context budget for the extraction preview's jump-to-source viewer
-- (WR batch segment B — a thin chunk must be readable inside its
-- surrounding original text).
--
-- Why this key exists:
--   The live incident was chunk #148477 — 13 characters of original text that
--   the source viewer showed in isolation, so the user could not tell WHY the
--   extractor found nothing there. The viewer now also fetches up to three
--   preceding chunks of the same document; this key caps how much neighbour
--   text travels (over budget the farthest neighbours drop first). The write
--   side validates the same window; the reader fails closed to the factory
--   value. Screen description carries only operator information.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.chunk-context-chars', '4000', '系统', 'string', 0, '分块回看上下文预算字数（默认 4000，允许 1000~20000；查看分块原文时附带前邻块正文的总字数上限，超出预算时最远的邻块先不显示。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
