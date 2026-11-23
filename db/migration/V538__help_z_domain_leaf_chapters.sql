
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no)
SELECT 'Z',
 '造数工厂：测试数据批量生成的配方',
 '## 这一页能做什么
把「怎么造一条合格数据」写成配方（字段规则、取值范围、关联关系），一键批量生成，产物进数据池供用例引用——不用手写一百条假订单。入口：造数与资产 → 造数工厂。

## 怎么用
1. 新建配方：定义字段与规则（类型、范围、枚举、关联字段），保存后可反复执行。
2. 批量生成时按配方逐条校验，不合格的当场剔除并在结果里点名第几条哪个字段违例。
3. 生成产物作为数据资产供用例、套件与批次引用，换项目换环境只换参数不换用例。

## 操作背后系统做了什么
规则先行、生成即校验：脏数据不会混进池子；配方与产物分账留存，谁造的、按什么造的、造了多少随时可回看。',
 (SELECT COALESCE(MAX(sort_no),0)+1 FROM tp_help_doc t2 WHERE t2.domain_code='Z')
WHERE NOT EXISTS (SELECT 1 FROM tp_help_doc t WHERE t.domain_code='Z' AND t.title LIKE '造数工厂%');

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no)
SELECT 'Z',
 '用例标签：分类字典与筛选统计的底座',
 '## 这一页能做什么
维护按模块、优先级等维度的用例分类字典；标签打得准，筛选和统计才准。入口：造数与资产 → 用例标签。

## 怎么用
1. 新建标签（名称、颜色、说明），在用例详情或列表批量挂到用例上。
2. 标签改名或删除自动同步到所有引用它的用例，不会出现「标签没了用例还挂着」的悬空状态。
3. 列表页、报告与质量看板都按这套标签做维度切分。

## 操作背后系统做了什么
标签字典项目级与系统级并集可见；引用计数在服务端维护，删除前先算清影响面，同步随改名即时生效。',
 (SELECT COALESCE(MAX(sort_no),0)+1 FROM tp_help_doc t2 WHERE t2.domain_code='Z')
WHERE NOT EXISTS (SELECT 1 FROM tp_help_doc t WHERE t.domain_code='Z' AND t.title LIKE '用例标签%');

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no)
SELECT 'Z',
 '资产池：跨项目共享的测试资产沉淀',
 '## 这一页能做什么
把可复用的测试资产（账号、数据集、公共步骤件等）集中沉淀，跨项目共享引用，避免每个项目各造一份、各自腐烂。入口：造数与资产 → 资产池。

## 怎么用
1. 资产入库时标注归属与可见范围；系统级资产在本组全部项目的列表与装配通道默认可见。
2. 项目引用资产而非复制副本：资产更新一次，引用方全部受益。
3. 不再使用的资产停用而非删除，历史引用仍可追溯。

## 操作背后系统做了什么
可见域=「本项目 ∪ 系统级桶」并集，由服务端统一判定；引用关系服务端计数，越权与失效引用在下发前被拦截点名。',
 (SELECT COALESCE(MAX(sort_no),0)+1 FROM tp_help_doc t2 WHERE t2.domain_code='Z')
WHERE NOT EXISTS (SELECT 1 FROM tp_help_doc t WHERE t.domain_code='Z' AND t.title LIKE '资产池%');

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no)
SELECT 'Z',
 '智能探索：找出还没测到的角落',
 '## 这一页能做什么
让系统根据已有用例与页面结构给出「还没测到的角落」建议，补测有方向。入口：造数与资产 → 智能探索。

## 怎么用
1. 选定项目与目标页面发起探索，系统产出覆盖建议清单。
2. 逐条评估建议：采纳的转成用例草稿进评审门，不采纳的可直接忽略。
3. 探索过程留证据链（截图/轨迹），结果可回放核对。

## 操作背后系统做了什么
建议只是建议——采纳与否由人决定，系统不会替你自动加用例；探索动作受执行总闸与安全边界约束，不碰生产地址。',
 (SELECT COALESCE(MAX(sort_no),0)+1 FROM tp_help_doc t2 WHERE t2.domain_code='Z')
WHERE NOT EXISTS (SELECT 1 FROM tp_help_doc t WHERE t.domain_code='Z' AND t.title LIKE '智能探索%');

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no)
SELECT 'Z',
 '角色卡：按业务角色画像造数造场景',
 '## 这一页能做什么
按业务角色（普通用户/店长/审核员……）画像生成配套的账号数据与操作场景，权限类用例造数不再拍脑袋。入口：造数与资产 → 角色卡。

## 怎么用
1. 定义角色：关联权限集合、典型操作路径与所需数据形态。
2. 生成场景时按角色画像自动备齐账号与数据，用例直接引用角色而非散装配方。
3. 越权与边界用例可由同一角色卡反向生成（该看的不该看的各一条）。

## 操作背后系统做了什么
角色到权限的映射与后端权限键同源校验；造数走造数工厂同一条校验链，脏角色画像在下发前点名。',
 (SELECT COALESCE(MAX(sort_no),0)+1 FROM tp_help_doc t2 WHERE t2.domain_code='Z')
WHERE NOT EXISTS (SELECT 1 FROM tp_help_doc t WHERE t.domain_code='Z' AND t.title LIKE '角色卡%');

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no)
SELECT 'Z',
 '登录态账本：一次登录，处处复用',
 '## 这一页能做什么
登录态（浏览器会话/令牌）沉淀成账本条目，API 用例、UI 用例与监控消费共用——不必每条用例重新登录被测系统。入口：造数与资产 → 登录态账本。

## 怎么用
1. 采集或生成登录态后入账，标注归属项目与形态（storageState 快照/令牌引用）。
2. 用例执行时按条目名引用，运行时注入；口令等敏感材料始终留在凭据保险箱，账本只存会话产物。
3. 过期或被被测系统登出的条目会在使用时点名，重新采集即可续上。

## 操作背后系统做了什么
快照文件走对象存储带签名访问，账本行不落敏感明文；引用前服务端校验归属与时效，失效条目诚实拒绝并指路重新采集。',
 (SELECT COALESCE(MAX(sort_no),0)+1 FROM tp_help_doc t2 WHERE t2.domain_code='Z')
WHERE NOT EXISTS (SELECT 1 FROM tp_help_doc t WHERE t.domain_code='Z' AND t.title LIKE '登录态账本%');

UPDATE tp_help_doc
   SET body_md = replace(body_md,
       '权限类用例造数不再拍脑袋。',
       E'权限类用例造数不再拍脑袋。\n- **凭据保险箱**：测试账号口令集中托管，步骤按名引用，运行时注入、日志与截图自动打码。\n- **登录态账本**：登录一次沉淀成会话资产，API/UI 用例与监控共用，失效点名指路。')
 WHERE domain_code = 'Z' AND sort_no = 1
   AND body_md NOT LIKE '%凭据保险箱%';
