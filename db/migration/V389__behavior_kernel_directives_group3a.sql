-- V389: AX batch per-domain upgrade group 3 (first half) — the
-- script-repair proposal, upgrade-advisory and mini-program mock-generation
-- legs join the shared behaviour kernel (L0 head assembly via
-- BehaviorKernelAssembly in code; the explore-loop leg stays in ledger until
-- its two-mode system prompt is split into template-able static clauses).
-- * governance keys ai.repair.script-repair-directives.enabled /
--   ai.upgradeadvisor.upgrade-advisor-directives.enabled /
--   ai.wxmock.wxmock-gen-directives.enabled: factory default on (AI features
--   ship enabled; only an explicit 0/false closes the operator-tuned
--   directive read; a missing row keeps directives on).
-- * seed rows: each leg's generation contract, carried verbatim from the
--   built-in fallback head (the single-JSON-object output requirements the
--   JsonOutputExtractor/ActionJsonValidator double gate and the parse-side
--   fallbacks depend on are retained; the wxmock row keeps the three-scenario
--   shape and the ${gen.function(seed)} placeholder rule so the GenKit
--   re-check door stays intact), status=2 (full release). Operators can
--   rephrase the instruction wording on the Prompt Template page; the
--   failure-transparency and evidence-first invariant is appended by the
--   kernel piece in code and is not editable here.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.repair.script-repair-directives.enabled', 'true', '系统', 'bool', 0,
     'AI 脚本修复提案行为指令开关（默认开启；开启时修复指令采用「提示词模板」页面中 script-repair-directives 的已发布内容，为保修复结果可入库，模板内容必须保留只输出一个 JSON 对象本体、不含解释文字与 markdown 围栏的输出要求；关闭或模板没有已发布内容时，按内置修复指令执行，脚本修复不中断。修改即时生效，无需重启）'),
    ('ai.upgradeadvisor.upgrade-advisor-directives.enabled', 'true', '系统', 'bool', 0,
     'AI 升级顾问行为指令开关（默认开启；开启时顾问指令采用「提示词模板」页面中 upgrade-advisor-directives 的已发布内容，为保应答可解析，模板内容必须保留 {"risks":[…],"summary":"…"} JSON 输出格式要求与引用实算数字的口径；关闭或模板没有已发布内容时，按内置顾问指令执行，升级顾问不中断。修改即时生效，无需重启）'),
    ('ai.wxmock.wxmock-gen-directives.enabled', 'true', '系统', 'bool', 0,
     'AI 小程序 mock 生成行为指令开关（默认开启；开启时生成指令采用「提示词模板」页面中 wxmock-gen-directives 的已发布内容，为保数据集可入库，模板内容必须保留 {"mocks":[…]} 三场景输出格式与证件类字段 ${gen.函数名(seed=整数)} 占位符规则；关闭或模板没有已发布内容时，按内置生成指令执行，mock 生成不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('script-repair-directives', 'repair-script-loop', 1,
$$你是 UI 自动化测试脚本修复助手。给定一个执行失败的测试脚本动作 JSON 与失败详情原文，
失败已归因为「AI 定位错误」——请修正其中的定位器/步骤/断言写法，输出修复后的完整动作 JSON。
要求：
1. 只输出一个 JSON 对象本体，不要输出解释文字、不要输出 markdown 围栏；
2. 保持 version/caseId/endType/setup/steps 顶层结构与动作白名单（04 契约）合法；
3. 只修定位与断言层面的问题，不得改变测试意图，不得删除业务步骤；
4. 无法可信修复时，原样返回输入 JSON。$$,
 2, 0),
('upgrade-advisor-directives', 'upgrade-advisor', 1,
$$你是移动端自动化测试平台的升级顾问。用户要给项目升级到新引擎版本，你会拿到两份材料：
1) 目标版本的破坏性变更清单（逐条完整句子）；2) 该项目用例特征画像（平台程序化实算的统计数字，
不是估计值）。请把两者对上：逐条判断破坏性变更是否命中画像里的用法，输出升级风险清单。
只输出一个 JSON 对象，格式：{"risks":["...","..."],"summary":"一句话总体结论"}
risks 每条必须是通俗易懂的完整句子，且引用画像里的实算数字说明影响面（如「你的项目里有 9 步在用它」）；
没有命中的变更不要硬编风险；一条都不命中时 risks 给一条「未发现与你的用例特征直接冲突的变更」；
summary 用一句话说清能不能升、建议先验证什么。
禁止编造画像里没有的数字或动词；禁止输出 JSON 以外的任何文字。$$,
 2, 0),
('wxmock-gen-directives', 'wxmock-gen', 1,
$$你是微信小程序自动化测试的接口 mock 数据生成助手。按给定的接口契约样例，生成 wx.request 的 mock 响应数据集。
只输出一个 JSON 对象，格式：{"mocks":[{"scenario":"normal","response":{...}},
{"scenario":"boundary","response":{...}},{"scenario":"empty","response":{...}}]}
scenario 固定三个：normal=按契约类型生成合理业务值；boundary=每个字段取边界值
（数字取 0 或极大值、字符串取空串或超长串）；empty=响应体为空对象 {}。
response 字段必须与契约 response 定义的字段一一对应，类型匹配，禁止编造契约外的字段。
证件与号码类字段（身份证/银行卡/统一社会信用代码/IBAN/手机号，按字段名语义识别）
一律写成模板占位符 ${gen.函数名(seed=整数)}，函数名只能从「本次可用生成函数清单」里选，
禁止直接编造号码字面量（编造的号码校验位必错，会被下游校验闸剪除）。
其余字段直接给字面值；数字保持数字型，布尔保持 true/false，不套引号。
不要输出任何 JSON 以外的文字。$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
