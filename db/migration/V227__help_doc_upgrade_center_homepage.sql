-- Migration: refresh the upgrade-center help chapter with the third-party homepage
-- column (red-line 8 doc sync; consumer = upgrade-center page help anchor openHelp('K')).
-- What this segment lands and why: the component-version panorama table now carries an
-- official-homepage quick link per ledger row (icon opens the vendor site in a new tab;
-- self-built/unknown families honestly show a dash — no dead links). The chapter body
-- gains the corresponding usage line and one system-behind explanation so the help text
-- matches the shipped UI.
-- Discipline: the $mdq$ body carries zero table names / config keys / feature codes
-- (same scrub posture as the original chapter segment); idempotent via
-- ON CONFLICT (domain_code, sort_no) DO UPDATE.
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('K', '升级中心：发版前后这里看什么', $mdq$
## 这一页能做什么
升级中心回答两个问题：「这个版本会带来什么」和「我的节点升不升得动」。管理员发版时登记一条发布清单，页面就呈现当前发布版本横幅、版本变更清单（破坏性变更逐条说明+部件支持矩阵）、升级引导单（可导出可打印），以及节点兼容性体检卡——逐个执行器节点对照协议兼容区间给出兼容/不兼容结论。「版本全景」页签按节点逐行展示部件版本三色判定，第三方部件行尾还带官网快捷入口，点一下新标签直达官方站核对版本与文档。

## 怎么用
入口：左侧菜单「执行中心 → 升级中心」。
1. 清单未导入时页面显示诚实空态与指引，不会报错也不会假装判定；请先由管理员登记或导入发布清单。
2. 顶部「版本变更清单」打开抽屉，按版本降序逐条看破坏性变更与部件矩阵。
3. 「升级引导单」打开本次升级的操作单：建议升级路径、破坏性变更逐条、兼容区间，右上角可导出文件或打印交给操作人。
4. 「兼容性体检」展开体检卡：每行一个执行器节点，绿=兼容、红=不兼容并写明原因。
5. 「版本全景」页签逐节点看部件版本台账；行尾官网图标点开后在新标签打开该部件的官方网站，平台自研部件与来源不明的部件显示灰色短横（没有官网就是没有，不放死链）。
6. 页头「帮助」随时回看本页说明。

## 操作背后系统做了什么
- 登记发布清单是版本唯一行：同一版本重复登记=覆盖更新（幂等），发版修正不用先删再建。
- 执行器注册和心跳都会对照最新发布清单的兼容区间做校验：区间外的节点拒绝入池并并说明需要哪个版本段，管理员会收到强制告警；已经在池的节点升级平台后变得不兼容时，会被批量标红并停止派发任务（宁可排队不误派），清单补齐或节点升级后自动恢复，无需人工干预。
- 体检卡每一行的结论是现场计算的，与注册/心跳闸同一套判定口径，不存在第二套账；清单为空时体检卡明确说「暂不判定」，不拿旧数据凑数。
- 数据库迁移永远不自动执行：这是数据安全底线，引导单会把要手动执行的步骤逐条列出来。
- 官网入口的归属判定只有一个出处：能对上自动化引擎目录的部件取其登记的文档站点，常见第三方运行工具按内置映射表给官方站，平台自研服务与未知部件一律留空——页面不做第二次猜测，引擎目录改了哪边同步生效。

## 常见问题
- 横幅有了，体检卡却全灰？——发布清单没填执行器兼容区间（上下界都为空=不设限），全部节点按兼容处理，属正常。
- 某节点红了怎么办？——按红行「说明」列的版本区间要求升级该节点的执行器，或联系管理员核对清单登记的区间是否写反；平台侧无需手工改状态。
- 引导单导出的是文本格式，能用吗？——导出为便于粘贴到工单/群聊的纯文单，打印走浏览器打印，内容一致。
- 官网列为什么有的行是灰色短横？——该部件是平台自研服务或来源无法确认，系统宁可不显示也不配一个可能出错的链接；要修正可在引擎中心维护该引擎的安装文档地址。
$mdq$, 2, '2.73')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET title = EXCLUDED.title, body_md = EXCLUDED.body_md, version = EXCLUDED.version, updated_at = now();
