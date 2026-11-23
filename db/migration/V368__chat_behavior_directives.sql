-- AX batch : behaviour directives of the online dialog become editable
-- without a redeploy. The dialog assembly reads this family's latest
-- full-release row fresh on every turn (uncached hot-update discipline, same
-- as the generation-chain template resolver); the two-sentence built-in
-- constant stays as the conservative fallback for an off switch, a missing
-- row or a failing core read — the chain never breaks silently.
-- * governance key ai.chat.behavior-directives.enabled: factory default on
--   (AI features ship enabled; only an explicit 0/false closes them).
-- * seed row: the four clause groups (verify platform facts before answering
--   / plan multi-step tasks / disambiguate only when guessing is expensive /
--   self-check before answering and offer next steps). status=2 (full
--   release) — the dialog has no gray bucket; earlier versions on the same
-- Both inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.behavior-directives.enabled', 'true', '系统', 'bool', 0,
     '对话行为指令开关（默认开启；开启时 AI 对话按「提示词模板」页面中 chat-behavior-directives 的已发布内容约束回答方式，在该页面修改后下一轮对话即时生效；关闭或模板没有已发布内容时，按内置的基础说明回答，对话不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('chat-behavior-directives', 'chat-behavior', 1,
'你是 testPike 的在线 AI 助手，基于平台数据与知识库回答测试工作问题。不确定的内容直说并指路，绝不编造；写操作只会先出草案，需用户确认后才落地。

【查证作答】回答涉及平台事实（用例、执行记录、配置、统计数字）时，先用查询工具核实再下结论；查不到就直说没查到，并指引可以人工查看的页面位置，不用猜测填空。
【分步推进】任务需要多个步骤时，先列出步骤计划，再逐步执行，每一步确认结果后再进入下一步；中途失败时说明卡在哪一步、已经完成哪些。
【歧义处理】仅当问题缺少关键信息、且不同理解会导致明显不同的结果时，向用户提出一个带选项的澄清问题（最多一次）；信息基本够用时按最可能的理解直接回答，并在开头说明所做的前提假设。
【作答前自检】给出最终回答前自检：用户的原始问题是否已回答、每条关键结论是否有查询结果作为依据、结论与数据之间有没有矛盾处。回答结束后如果还有值得继续的方向，提出 1-3 条后续问题供用户选择。',
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
