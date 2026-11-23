-- Migration: K8 config group for dialog grounding .
--
-- Why this file exists:
--   Every dialog turn now grounds its answer in help chapters and knowledge
--   chunks before calling the model (retrieval-first, honest zero-hit). Three
--   knobs decide how much material survives ranking and how much of it reaches
--   the prompt: the citation cap (how many clickable sources a turn may show),
--   the per-citation excerpt window (a card shows the matched passage, never
--   the whole document), and the relevance floor (below it the turn is treated
--   as ungrounded and the answer must say "I don't know" instead of
--   improvising platform facts). All three are hot-updated from the config
--   page; the write side guards the int intervals in AiChatKeyRanges (K8
--   convention: no DB CHECK, service layer is the single source) and readers
--   fall back to these seeded defaults on missing or garbage values.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.grounding.max-citations', '8', '系统', 'int', 0, '单次回答最多引用的来源材料条数（默认 3；区间 1~10，热更即生效；超出数量的检索结果不会出现在回答的来源清单里）'),
    ('ai.chat.grounding.excerpt-chars', '160', '系统', 'int', 0, '每条来源摘录的最大字符数（默认 160；区间 40~600，热更即生效；来源卡片只展示命中的这一段文字，不展示整篇文档）'),
    ('ai.chat.grounding.min-score', '0.05', '系统', 'string', 0, '来源材料的相关性下限（默认 0.05；区间 0~1，热更即生效；低于该值视为没有命中，回答将直接说明不知道并指路，而不是勉强引用）')
ON CONFLICT (config_key) DO NOTHING;
