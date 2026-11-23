-- Replay the factory defaults of four context/stream keys whose caps were
-- relaxed in the seed line only: live databases kept the old, narrower values
-- while new installs already received the relaxed ones, so dev environments
-- ran tighter than the shipped defaults (defect ledger, scan).
-- Values and the on-screen description text are pinned to the seed state in
-- one statement; the IS DISTINCT FROM guard keeps replay a no-op once the
-- row already matches, so re-running this file writes nothing.
UPDATE tp_sys_config AS t
SET config_value = s.v,
    description  = s.d
FROM (VALUES
    ('ai.chat.first-frame-budget-seconds', '30',
     '对话首帧等待预算秒数（默认 30；区间 2~30，热更即生效；预算内未收到首个响应帧时连接以错误提示结束，不会无限等待）'),
    ('ai.ctx.memory-max-items', '128',
     '注入回答的项目记忆条目数上限（默认 128；区间 1~200，在本页修改即时生效；条目超出上限时按与本次提问的相关性取舍，不会整批拼入上下文）'),
    ('ai.ctx.session-keep-recent-turns', '20',
     '对话上下文保留的最近轮数（默认 20；区间 1~50，在本页修改即时生效；比这更早的历史自动折叠为一段摘要随每轮提供，长对话不会因此丢掉近期内容）'),
    ('ai.ctx.tool-block-max-tokens', '8000',
     '工具执行结果单次入袋的 token 预算（默认 8000；区间 200~20000，在本页修改即时生效；超出预算的长结果自动改为头尾摘录+全文落盘可检索，回答照常给出，不会整段丢失）')
) AS s(k, v, d)
WHERE t.config_key = s.k
  AND (t.config_value IS DISTINCT FROM s.v OR t.description IS DISTINCT FROM s.d);
