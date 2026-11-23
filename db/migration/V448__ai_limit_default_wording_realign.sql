-- Migration: realign capability-limit descriptions with their pinned defaults.
--
-- Why this file exists:
--   The capability-limit pinning batch raised the shipped values of ten AI
--   numeric keys (wide defaults so real tasks are not truncated) but their
--   settings-page descriptions still advertised the old narrow number.
--   Operators read the description as the factory default, so a stale
--   "default N" claim is wrong operational information. Values are correct
--   and stay untouched; only description wording follows the value here.
--   Idempotent: each statement only fires while the description differs.

UPDATE tp_sys_config
SET description = '对话首帧等待预算秒数（默认 30；区间 2~30，热更即生效；预算内未收到首个响应帧时连接以错误提示结束，不会无限等待）'
WHERE config_key = 'ai.chat.first-frame-budget-seconds'
  AND description IS DISTINCT FROM '对话首帧等待预算秒数（默认 30；区间 2~30，热更即生效；预算内未收到首个响应帧时连接以错误提示结束，不会无限等待）';

UPDATE tp_sys_config
SET description = '单次回答最多引用的来源材料条数（默认 8；区间 1~10，热更即生效；超出数量的检索结果不会出现在回答的来源清单里）'
WHERE config_key = 'ai.chat.grounding.max-citations'
  AND description IS DISTINCT FROM '单次回答最多引用的来源材料条数（默认 8；区间 1~10，热更即生效；超出数量的检索结果不会出现在回答的来源清单里）';

UPDATE tp_sys_config
SET description = '单条对话问题的图片附件上限（默认 8 张；超出时发送会被拒绝并提示本次张数与上限，改此值即时生效）'
WHERE config_key = 'ai.chat.image.max-attachments'
  AND description IS DISTINCT FROM '单条对话问题的图片附件上限（默认 8 张；超出时发送会被拒绝并提示本次张数与上限，改此值即时生效）';

UPDATE tp_sys_config
SET description = '单条提问最多引用的对象数量（默认 20；区间 1~20，热更即生效；超出数量时提问会被拒绝并提示当前引用个数，不会悄悄丢掉一部分）'
WHERE config_key = 'ai.chat.ref.max-objects'
  AND description IS DISTINCT FROM '单条提问最多引用的对象数量（默认 20；区间 1~20，热更即生效；超出数量时提问会被拒绝并提示当前引用个数，不会悄悄丢掉一部分）';

UPDATE tp_sys_config
SET description = '单个对话会话的 token 预算上限（默认 2000000，热更即生效；会话内累计用量超出后本轮不再继续调用模型，用量中心可查已消耗部分）'
WHERE config_key = 'ai.chat.session-budget-tokens'
  AND description IS DISTINCT FROM '单个对话会话的 token 预算上限（默认 2000000，热更即生效；会话内累计用量超出后本轮不再继续调用模型，用量中心可查已消耗部分）';

UPDATE tp_sys_config
SET description = '注入回答的项目记忆条目数上限（默认 128；区间 1~200，在本页修改即时生效；条目超出上限时按与本次提问的相关性取舍，不会整批拼入上下文）'
WHERE config_key = 'ai.ctx.memory-max-items'
  AND description IS DISTINCT FROM '注入回答的项目记忆条目数上限（默认 128；区间 1~200，在本页修改即时生效；条目超出上限时按与本次提问的相关性取舍，不会整批拼入上下文）';

UPDATE tp_sys_config
SET description = '对话上下文保留的最近轮数（默认 20；区间 1~50，在本页修改即时生效；比这更早的历史自动折叠为一段摘要随每轮提供，长对话不会因此丢掉近期内容）'
WHERE config_key = 'ai.ctx.session-keep-recent-turns'
  AND description IS DISTINCT FROM '对话上下文保留的最近轮数（默认 20；区间 1~50，在本页修改即时生效；比这更早的历史自动折叠为一段摘要随每轮提供，长对话不会因此丢掉近期内容）';

UPDATE tp_sys_config
SET description = '工具执行结果单次入袋的 token 预算（默认 8000；区间 200~20000，在本页修改即时生效；超出预算的长结果自动改为头尾摘录+全文落盘可检索，回答照常给出，不会整段丢失）'
WHERE config_key = 'ai.ctx.tool-block-max-tokens'
  AND description IS DISTINCT FROM '工具执行结果单次入袋的 token 预算（默认 8000；区间 200~20000，在本页修改即时生效；超出预算的长结果自动改为头尾摘录+全文落盘可检索，回答照常给出，不会整段丢失）';

UPDATE tp_sys_config
SET description = '探索访问页面上限（默认 64；无进展/预算终止判据，B9/T209）'
WHERE config_key = 'ai.explore.max-pages'
  AND description IS DISTINCT FROM '探索访问页面上限（默认 64；无进展/预算终止判据，B9/T209）';

UPDATE tp_sys_config
SET description = '探索单次最大步数上限（默认 128；预算快照来源，B9/T209）'
WHERE config_key = 'ai.explore.max-steps'
  AND description IS DISTINCT FROM '探索单次最大步数上限（默认 128；预算快照来源，B9/T209）';
