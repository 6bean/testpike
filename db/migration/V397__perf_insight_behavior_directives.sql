-- V397: AX batch per-domain upgrade group 3b half-leg — the load-test
-- run-insight read-out joins the shared behaviour kernel (L0 head assembly
-- via BehaviorKernelAssembly in code). The dialog repair-draft leg needs no
-- new rows here: it shares the B4 repair vocabulary (key
-- ai.repair.script-repair-directives.enabled and family
-- script-repair-directives/repair-script-loop seeded by V389), so one
-- operator edit on the Prompt Template page benefits both repair legs.
-- * governance key ai.perf.insight-directives.enabled: factory default on
--   (AI features ship enabled; only an explicit 0/false closes the
--   operator-tuned directive read; a missing row keeps directives on).
-- * seed row: the read-out contract carried verbatim from the built-in
--   fallback head (facts-verbatim / never-rewrite-verdict / insight-JSON
--   requirements retained so the strict parser and the core-side re-checks
--   stay intact), status=2 (full release). Operators can rephrase the
--   instruction wording on the Prompt Template page; the failure-transparency
--   and evidence-first invariant is appended by the kernel piece in code and
--   is not editable here.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.perf.insight-directives.enabled', 'true', '系统', 'bool', 0,
     '压测归因解读行为指令开关（默认开启；开启时解读指令采用「提示词模板」页面中 perf-insight-directives 的已发布内容，为保解读可采用，模板内容必须保留「数字只引用事实、不改写判定结论」约束与 insight JSON 输出格式要求；关闭或模板没有已发布内容时，按内置解读指令执行，归因解读不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('perf-insight-directives', 'perf-insight-readout', 1,
$$你是接口压测结果的归因解读助手。用户会给出一段压测归因事实（结论、分位耗时、错误率、吞吐、错误分类计数、瓶颈线索）。
你的任务只用简体中文把这些事实解释成一段平实易懂的说明：说清压力表现如何、主要问题集中在哪里、下一步值得排查什么。
数字、门禁阈值、错误分类计数一律只能原样引用事实中出现过的内容，禁止编造或换算事实里没有的指标；
结论通过与否以事实给出的结论为准，禁止改写判定。
只输出一个 JSON 对象，不要输出其他文字，格式：
{"insight":"一段不超过200字的中文解读"}$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
