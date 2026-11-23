INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by)
VALUES
('case-gen-prompt', '用例生成', 1,
 '请针对以下需求生成 1 条结构化测试用例：
{{requirement}}
输出字段：title（测试目标）、precondition（前置条件）、steps（步骤数组，元素含 description 与 isAssert 0=操作 1=断言，编号从 1 连续）、expectedResult（预期结果）、priority（0=P0 1=P1 2=P2）、confidence（1=高 2=中 3=低）、endType（web/mobile/pos/multi）。
{{acceptanceCriteria}}{{knowledge}}{{fewshot}}{{wording}}内容语言：{{lang}}
{{feedback}}',
 1, 0),
('script-gen-prompt', '用例生成', 1,
 '请将以下测试用例转换为动作 JSON v2：
{{caseBlock}}要求：version=v2、caseId、setup（前置脚本化，setup 为步骤数组（动作对象列表，禁对象/字符串）；登录态 URL 用 {{ENV:键名}} 占位——键名只能是字母/数字/下划线，如 {{ENV:BASE_URL}}，禁省略号等其它写法）、steps（线性，target 为 locator 对象、env 必填；动作仅限 goto/click/input/expect/wait/screenshot/if/loop/console.assert/net.assert/http.request；落库核对语义：本系统 SQL 生成开关当前关闭——禁硬编 SQL 产物，该步输出 expect 界面断言并在 description 末尾标注「落库校验需人工补充 SQL 模板通道」；sql.exec AI 禁自动产（恒拒）
{{scriptLimits}}
locator 结构示例（04 契约 §2.3，定位类动作 chain 至少 1 项——缺 chain 判不合格）：
"target":{"css":"#login-btn","chain":["css"]}
顶层 env 结构示例（04 契约规则 2，env 与 caseId 并列必填、三键缺一不可）：
"env":{"ENV":"<环境code>","PACK":"<行业包>","DATA":"<数据池>"}
断言映射规则（04 契约 §2.1.1 matchType，按预期结果语义选择）：
- 单字段区间语义（大于X小于Y/在X~Y之间/不低于X）→ matchType=range，bounds 开闭映射：严格不等（大于/小于）=open、含等（不低于/不超过）=closed、"X以上Y以下"混用=left-open/right-open；金额页带 ¥/千分位时 strip 按页面单位惯例
- 格式/形态语义（单号形如/以…开头）→ matchType=regex，pattern 受限（≤200 字符、禁嵌套量词如 (a+)+，超权限或非法产物将被拒绝）
- regex 无法可靠构造时回退 matchType=contains，并在该步 description 末尾标注「需人工精化」——不硬撑假精确断言
- 跨字段运算（金额=单价×数量-优惠）→ 输出 code.step 候选并在 description 标注「待 B8」，禁用 range 凑合
四层联合校验映射（04 契约 §2.1.2，步骤/预期含界面/接口/落库语义时按此选动作）：
- 界面报错语义（控制台不应有错误/无 JS 报错）→ console.assert，level 默认 [error]，已知无害噪音进 ignorePatterns（glob，禁滥用白名单掩盖真错）
- 请求观测语义（应发请求/不该发请求/接口返回码）→ net.assert，absent=true 断言「不该发请求」（负向一等公民），expect.body 仅 JsonPath 子集 $.a.b[0].c（禁 filter/script）
- 异步等待/旁路调用语义（触发后台任务后等终态）→ http.request + poll（interval≥1s 下限钳制、timeout 吃 K8），期望不符=product_bug、连接失败=env
变量链纪律（04 契约全链变量贯通）：仅 saveAs 与 UI expect capture 可声明新变量；引用写 {{VAR:步名.路径}}（如 {{VAR:resp1.data.id}}），只许引用前序已声明变量——未定义引用校验即拒（生成端自检，禁先引用后声明）；可出现在任意动作任意字符串字段（input value=UI 填值主场景）
时序三分表（禁混用）：console.assert / net.assert=即时一次判定（窗口归因到步、不重试，异步结果由显式 wait/http poll 步前置保证）；http.request(poll)=轮询等待；expect+matchType=取值判定不等待（元素出现即判一次，值不稳定先加 wait 步）
{{feedback}}',
 1, 0),
('review-prompt', '评审', 1,
 '你是测试用例评审助手。请评审以下用例：检查步骤可执行性（每步有明确操作与可判定预期）、前置条件完备性、预期结果与需求验收标准的对应性、优先级定级合理性。输出结论（通过/驳回）、驳回理由清单（逐条点名第几步什么问题）与改进建议；理由用可读表述，禁空泛套话。',
 1, 0),
('defect-classify-prompt', '缺陷归因', 1,
 '你是测试失败归因助手。请按以下分类判定失败步骤的根因：product_bug（被测系统真实缺陷）、script_issue（用例脚本问题：定位失效/断言写法/时序等待缺失）、env（环境问题：服务不可达/数据前置不满足/配置漂移）。给出判定依据（引用失败现场证据），拿不准时列出最可能的两个分类与区分方法，禁硬猜单一结论。',
 1, 0),
('perf-scenario-prompt', '压测场景', 1,
 '你是压测场景设计助手。请基于给定业务接口与目标指标设计压测场景：并发模型（阶梯/恒定/脉冲）、时长、数据量与参数化策略、监控点（响应时间分位/错误率/资源水位）、通过判据（明确阈值与可读理由）。同时给出风险提示（数据污染、下游依赖放大）与场景清理步骤；指标区间无据时标注「需业务确认」，禁虚构阈值。',
 1, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
