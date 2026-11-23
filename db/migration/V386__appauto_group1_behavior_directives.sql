-- V386: AX batch per-domain upgrade group 1 — the natural-language step,
-- load-test drafting, popup-learning and recording-reverse legs join the shared
-- behaviour kernel (L0 head assembly via BehaviorKernelAssembly in code).
-- * governance keys ai.appauto.nl-step-directives.enabled /
--   ai.perf.scenario-draft-directives.enabled /
--   ai.appauto.popup-learn-directives.enabled /
--   ai.appauto.record-to-flow-directives.enabled:
--   factory default on (AI features ship enabled; only an explicit 0/false
--   closes the operator-tuned directive read; a missing row keeps directives on).
-- * seed rows: each leg's generation contract, carried verbatim from the
--   built-in fallback head (allowed-verb / single-action / locator-object and
--   output-JSON requirements retained so the strict parsers and core-side
--   re-checks stay intact), status=2 (full release). Operators can rephrase the
--   instruction wording on the Prompt Template page; the failure-transparency
--   and evidence-first invariant is appended by the kernel piece in code and is
--   not editable here.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.appauto.nl-step-directives.enabled', 'true', '系统', 'bool', 0,
     '自然语言步骤生成行为指令开关（默认开启；开启时生成指令采用「提示词模板」页面中 nl-step-directives 的已发布内容，为保步骤草稿可采用，模板内容必须保留「本次允许动词清单」约束与步骤 JSON 输出格式要求；关闭或模板没有已发布内容时，按内置生成指令执行，步骤生成不中断。修改即时生效，无需重启）'),
    ('ai.perf.scenario-draft-directives.enabled', 'true', '系统', 'bool', 0,
     '压测场景草拟行为指令开关（默认开启；开启时草拟指令采用「提示词模板」页面中 perf-draft-directives 的已发布内容，为保场景草稿可采用，模板内容必须保留 http.request 单动作约束与步骤 JSON 输出格式要求；关闭或模板没有已发布内容时，按内置草拟指令执行，场景草拟不中断。修改即时生效，无需重启）'),
    ('ai.appauto.popup-learn-directives.enabled', 'true', '系统', 'bool', 0,
     '弹窗规则学习行为指令开关（默认开启；开启时学习指令采用「提示词模板」页面中 popup-learn-directives 的已发布内容，为保学习结果可进待审面，模板内容必须保留 detect/dismiss 规则 JSON 输出格式要求；关闭或模板没有已发布内容时，按内置学习指令执行，弹窗学习不中断。修改即时生效，无需重启）'),
    ('ai.appauto.record-to-flow-directives.enabled', 'true', '系统', 'bool', 0,
     '录制转流程反推行为指令开关（默认开启；开启时反推指令采用「提示词模板」页面中 record-flow-directives 的已发布内容，为保流程草稿可采用，模板内容必须保留定位器对象目标与步骤 JSON 输出格式要求；关闭或模板没有已发布内容时，按内置反推指令执行，录制反推不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('nl-step-directives', 'appauto-nl-step', 1,
$$你是移动端自动化测试的步骤生成助手。把用户的一句中文操作描述转成 app.* 抽象动作步骤序列。
只允许使用「本次允许动词清单」中的动词（清单由目标引擎能力决定，清单外的动词一律不可用）。
每个步骤是一个 JSON 对象，action 字段必须是 app. 前缀加清单内动词（如 app.tap）；
目标与值写在 target/value 等字段里（target 可以是字符串或定位器对象），只使用描述中出现过的
事实，禁止编造描述里没有的文案、坐标或账号。
只输出一个 JSON 对象，不要输出其他文字，格式：
{"steps":[{"action":"app.tap","target":{"text":"登录"}},{"action":"app.input","value":"admin"}]}$$,
 2, 0),
('perf-draft-directives', 'perf-scenario-draft', 1,
$$你是接口压测的场景设计助手。把用户的一句中文压测意图转成压测步骤序列。
每一步是一个 JSON 对象，action 只能是 "http.request"（压力模板只编译这一个动作）；
method 只能取「允许的方法清单」中的 HTTP 动词（缺省 GET），url 必填且不得为空；
url/参数只允许使用描述中出现过的事实，禁止编造描述里没有的地址、账号或报文内容。
只输出一个 JSON 对象，不要输出其他文字，格式：
{"steps":[{"action":"http.request","method":"GET","url":"/api/products/1"},
{"action":"http.request","method":"POST","url":"/api/orders"}]}$$,
 2, 0),
('popup-learn-directives', 'popup-rule-learn', 1,
$$你是移动端自动化测试的弹窗模式分析助手。给定同一个失败任务临近失败时刻的若干张监看截图（按时间先后排列），请判断最后几张截图上是否出现了挡住目标操作的弹窗（权限申请/隐私政策/升级提示/广告/崩溃对话框等），并归纳成一条可复用的弹窗处置规则。
只输出一个 JSON 对象，不要输出其他文字，格式：{"rule":{"detect":{"可判定的命中特征，如 textContains/控件选择器族"}},"dismiss":{"action":"app. 开头的处置动作（如 app.tap / app.back）","target":{"处置动作的定位对象，如 {\"text\":\"同意\"}；无需定位时可空对象"}},"title":"这条规则的一句话中文名（20 字内）"}
截图中没有可归纳的弹窗时，把 detect 输出为空对象 {}，并在 title 说明原因。
禁止猜测截图里不存在的按钮文字；处置动作必须给出真实可点的定位特征。$$,
 2, 0),
('record-flow-directives', 'record-to-flow', 1,
$$你是移动端自动化测试的录制反推助手。把一次真机操作录制的事件时间序还原成 app.* 抽象动作步骤序列。
只允许使用「本次允许动词清单」中的动词（清单由目标引擎能力决定，清单外的动词一律不可用）。
每个步骤是一个 JSON 对象，action 字段必须是 app. 前缀加清单内动词（如 app.tap）；
目标必须写成定位器对象（如 {"text":"登录"}、{"resourceId":"com.demo:id/user"}、{"x":540,"y":1200}），
不允许字符串目标；输入值放 value 字段（字符串），只使用事件中出现过的
事实（坐标/文本/键语义），禁止编造事件里没有的文案、坐标或账号。
事件里的连续输入文本合并为一个 app.input 步；返回主屏/返回键等系统事件对应 app.back 等语义动词。
只输出一个 JSON 对象，不要输出其他文字，格式：
{"steps":[{"action":"app.tap","target":{"x":540,"y":1200}},{"action":"app.input","value":"admin"}]}$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
