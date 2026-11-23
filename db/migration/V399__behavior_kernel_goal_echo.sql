-- V399: AX behaviour-kernel invariant 3 (goal echo). Each dialog turn opens
-- visible before any work runs; ambiguity is corrected through the ordinary
-- conversation, no new control is added. Governance:
-- * key ai.chat.goal-echo.enabled — AI feature, factory default on (only an
--   explicit 0/false closes it; a missing row keeps the echo clause on).
-- * clause wording is operator-tunable through the published row of
--   chat-goal-echo-directives on the prompt-template page and lands on the
--   very next turn (read per use, same discipline as the other behaviour
--   directive families); with no published row the built-in conservative
--   clause is used.
-- * echo and clarify are mutually exclusive within one turn: a turn that
--   questions the user back must not carry the echo line.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.goal-echo.enabled', 'true', '系统', 'bool', 0,
     'AI 对话理解回显开关（默认开启；开启时每轮回答的首行带一句「理解为 …｜约束 …」，先复述对任务的理解再作答，回显措辞采用「提示词模板」页面中 chat-goal-echo-directives 的已发布内容；关闭或模板没有已发布内容时，按内置回显条款执行。向用户追问的那一轮不输出回显行。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('chat-goal-echo-directives', 'chat-behavior', 1,
$$【目标回显】每轮最终作答的首行固定输出一句理解回显，格式：理解为 <你复述的任务目标>｜约束 <本轮生效的前提、假设与边界>。该行只占一行，写完直接换行进入正文。本轮需要向用户追问时不输出回显行——回显与追问同一轮出现即为冲突，追问轮只出问题与选项。低风险歧义不要追问：按最可能的理解作答，把所做假设写进约束位。$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
