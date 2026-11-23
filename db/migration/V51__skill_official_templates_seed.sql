
INSERT INTO tp_skill (project_id, name, type, status, body, frontmatter, current_version, version, approved_at)
SELECT NULL, '用例质量检查规范', 1, 3, $casecheck$
# 用例质量检查规范（官方模板 · 用例检查类）

生成/评审测试用例时遵循以下检查项，逐条满足后再输出：

1. **标题**：动宾结构、可区分（「验证<功能点>在<条件>下<预期>」），禁「测试1」类无意义标题。
2. **前置条件**：登录态、数据准备、环境要求逐条列出；可由 setup 段脚本化的写清动作（配合 {{ENV:...}} 占位符纪律）。
3. **步骤**：每步一个原子操作，编号连续；可执行、可复现，禁「随意输入」「观察页面」类模糊措辞。
4. **预期结果**：可断言的具体值/状态（文本、跳转、数据落库），与步骤一一对应；断言优先精确匹配（matchType 见 04 契约 B10）。
5. **优先级**：核心链路=P0；主流程变体=P1；边界/异常=P2；罕见组合=P3。
6. **覆盖检查**：正常流 + 至少 1 条异常流 + 边界值（空/超长/特殊字符/并发）各 1 条。
7. **可追溯**：每条用例关联需求点（storyId），无归属用例标「待确认」。
$casecheck$,
       '{"name": "用例质量检查规范", "type": 1, "stages": ["case-gen"], "scope": "general", "version": 1}'::jsonb,
       1, 0, now()
WHERE NOT EXISTS (SELECT 1 FROM tp_skill WHERE COALESCE(project_id, 0) = 0 AND name = '用例质量检查规范');

INSERT INTO tp_skill (project_id, name, type, status, body, frontmatter, current_version, version, approved_at)
SELECT NULL, '脚本开发规范', 3, 3, $scriptstd$
# 脚本开发规范（官方模板 · 脚本开发类，stage2 注入）

将用例转换为动作 JSON v2 脚本时遵循（限额具体数值以 ai.script.* 热更注入行为准，勿凭记忆写死）：

1. **结构**：顶层必含 version=v2、caseId、env（ENV/PACK 三键缺一不可）、setup、steps；动作仅限词表（goto/click/input/select/expect/wait/screenshot/if/loop，旁路族见 04 契约 B11）。
2. **定位**：target 为 locator 对象且 chain 至少 1 项；优先 id/name/css 稳定选择器，禁 nth-child 类脆弱链；避免依赖生成的样式类名。
3. **等待**：开启智能等待时用元素级 wait（wait_for visible），禁堆固定 sleep；断言前确保状态稳定。
4. **断言**：expect 步给出可判定的具体期望值；区间/正则按 B10 matchType 映射规则，禁退化 contains 掩盖缺陷。
5. **数据**：测试数据经 {{DATA:...}} 数据池领取、环境值经 {{ENV:...}} 注入，禁明文账号密码入脚本。
6. **步骤数与嵌套**：≤ 注入限额；超限先拆分用例而非硬堆；loop/if 嵌套深度受 max-nest-depth 约束。
7. **失败策略**：按 fail-policy 生成（1=继续后续步骤记录失败 / 2=立即中止）；截图按 screenshot-mode 策略执行。
8. **可维护**：每步 description 中文说明意图；复用逻辑不复制粘贴（同一段操作 ≥2 处出现应抽 setup）。
$scriptstd$,
       '{"name": "脚本开发规范", "type": 3, "stages": ["script-gen"], "scope": "general", "version": 1}'::jsonb,
       1, 0, now()
WHERE NOT EXISTS (SELECT 1 FROM tp_skill WHERE COALESCE(project_id, 0) = 0 AND name = '脚本开发规范');

INSERT INTO tp_skill (project_id, name, type, status, body, frontmatter, current_version, version, approved_at)
SELECT NULL, '需求取数规范', 2, 3, $reqext$
# 需求取数规范（官方模板 · 需求补齐类，stage1/F2 注入）

从需求文档提取需求点并补齐用例时遵循：

1. **取数优先原文**：每条需求点必须能指回原文段落（可追溯）；文档未写的信息标「待确认」，禁臆造字段、接口、文案。
2. **显式 vs 隐含**：显式功能点逐条转录；隐含需求（校验规则、边界、异常提示、权限）按「输入域 + 状态迁移 + 角色矩阵」三问补齐，并标推断依据。
3. **数据字典对齐**：枚举/字典类需求先问业务全集与项目间差异（同一枚举在不同项目环境集合可不一致），禁只抄单一文档示例值。
4. **数值与阈值**：出现「上限/时效/阈值」必取具体值与单位；无值时列为待确认项，禁默认值顶替。
5. **验收标准**：每个需求点至少配 1 条可断言的验收准则（A5 覆盖检查依据）；准则含正例 + 反例。
6. **外部依赖标注**：涉及第三方（SSO/ESB/消息通道/外部平台）的需求点标注依赖与本地可达性，避免生成无法执行的用例。
7. **去重与合并**：同义需求合并为一条并保留全部出处；粒度以「一条需求点对应 1~N 条用例」为准，禁把整个模块当一条。
$reqext$,
       '{"name": "需求取数规范", "type": 2, "stages": ["doc-analysis", "case-gen"], "scope": "general", "version": 1}'::jsonb,
       1, 0, now()
WHERE NOT EXISTS (SELECT 1 FROM tp_skill WHERE COALESCE(project_id, 0) = 0 AND name = '需求取数规范');

INSERT INTO tp_skill_version (skill_id, version, body, frontmatter, status_at_snapshot, reason, approved_at, created_at)
SELECT s.id, 1, s.body, s.frontmatter, 3, '官方模板内置种子（M3.2 T170）', now(), now()
FROM tp_skill s
WHERE COALESCE(s.project_id, 0) = 0
  AND s.name IN ('用例质量检查规范', '脚本开发规范', '需求取数规范')
  AND NOT EXISTS (SELECT 1 FROM tp_skill_version v WHERE v.skill_id = s.id AND v.version = 1);

COMMENT ON TABLE tp_skill IS 'SKILL 定义（S1/S2；5 类型；系统级 project_id NULL；status 5 态审批链；迁移件 官方模板种子 3 份系统级 published）';
