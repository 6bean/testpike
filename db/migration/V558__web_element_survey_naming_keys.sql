-- V558: WE batch segment P3 — AI semantic naming for page-survey candidates.
-- * governance key ai.element.naming.enabled: factory default on (AI features
--   ship enabled; only an explicit 0/false closes the naming round, which then
--   burns zero model calls and writes zero ledger rows; a missing row keeps
--   naming on).
-- * seed row: the naming instruction head, carried verbatim from the built-in
--   conservative fallback so the key-echo and JSON output requirements stay
--   coupled to the parser; status=2 (full release). Missing row or a failed
--   read falls back to the built-in head — the prompt is never empty.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.element.naming.enabled', 'true', '系统', 'bool', 0,
     '页面元素测绘命名开关（默认开启；开启时测绘候选的业务语义名由大模型按「提示词模板」页面 web-element-survey-naming 的已发布内容生成，模板没有已发布内容时按内置命名指令执行；关闭或模型不可用时保留规则采集的名称，候选入账不受影响。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('web-element-survey-naming', 'web-element', 1,
$$你是页面元素命名员。给定同一页面内的交互元素候选清单（key=定位锚身份，text=可见文本，role/nearby=可选补充），为每个你能确定业务含义的候选起一个 12 字内的中文业务语义名。
只输出一个 JSON 对象，不要输出其他文字，格式：{"names":[{"key":"原样回传的key","name":"业务语义名"}]}
判断不了的候选直接省略；禁止编造名称；key 必须逐字原样回传。$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
