-- Migration: K8 config key group for the long-context quality batch .
--
-- Why this file exists:
--   The dialog/assembly readers need operator-tunable context budgets that never
--   require a redeploy: the per-tool-result token budget before a block is
--   downgraded to head/tail + stored full text with a retrieval anchor, the
--   number of newest dialog turns kept raw before older turns fold into the
--   rolling summary, the cap on injected project-memory items, and the
--   document group-summary cache switch (ships on; an operator can disable it
--   on the config page). All four are hot-updated from the config page;
--   the write side guards intervals/bools in AiCtxKeyRanges (K8 convention:
--   no DB CHECK, the service-layer table is the single source), and readers
--   clamp through AiCtxConfigResolver with the same intervals, falling back to
--   these seeded defaults on missing/garbage values.
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.ctx.tool-block-max-tokens', '8000', '系统', 'int', 0, '工具执行结果单次入袋的 token 预算（默认 2000；区间 200~20000，在本页修改即时生效；超出预算的长结果自动改为头尾摘录+全文落盘可检索，回答照常给出，不会整段丢失）'),
    ('ai.ctx.session-keep-recent-turns', '20', '系统', 'int', 0, '对话上下文保留的最近轮数（默认 6；区间 1~50，在本页修改即时生效；比这更早的历史自动折叠为一段摘要随每轮提供，长对话不会因此丢掉近期内容）'),
    ('ai.ctx.memory-max-items', '128', '系统', 'int', 0, '注入回答的项目记忆条目数上限（默认 30；区间 1~200，在本页修改即时生效；条目超出上限时按与本次提问的相关性取舍，不会整批拼入上下文）'),
    ('ai.ctx.doc-summaries-cache', 'true', '系统', 'bool', 0, '文档分段摘要缓存开关（默认关闭；开启后同一文档重新提取时复用已生成的分段摘要，不再重复逐段归纳；文档内容更新后缓存自动失效）')
ON CONFLICT (config_key) DO NOTHING;
