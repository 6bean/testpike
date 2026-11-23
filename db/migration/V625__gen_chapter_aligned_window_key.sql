-- Migration: chapter-aligned original-text injection window switch
-- .
--
-- Why this file exists:
--   The generation chain injects the anchor's hit chunk plus its ±1
--   neighbours as the "original text" block. A section rule routinely spans
--   split into seq 75-78, an anchor at chunk 78 never saw the field table at
--   seq 76), so the model generated from a half-context window. This key
--   routes the SAME window selection through a chapter-aligned face: every
--   sibling chunk carrying the hit chunk's chapter label rides in on top of
--   the ±1 rows (superset, nothing is dropped), while the injection budget
--   key and the honest truncation marker keep working unchanged. Factory
--   OFF; storing "true" enables, flipping back restores the legacy ±1 bytes
--   exactly (the reader fails closed to off on absent or dirty rows).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.chapter-aligned-window.enabled', 'false', '系统', 'bool', 0, '用例生成原文注入窗按章节扩展的开关（默认关闭；开启后注入命中分块所在章节的全部相邻分块，章节规则跨多块时不再遗漏章首内容，前后相邻分块与预算截断行为不变。改为 true 保存即生效，无需重启；改回 false 恢复仅注入前后相邻分块的旧形态）')
ON CONFLICT (config_key) DO NOTHING;
