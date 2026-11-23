-- Migration: add the interface-inventory help chapter to the API-testing domain
-- (red-line 8 doc sync; consumer = the interface inventory page help anchor).
-- Why: the page renders contract endpoint rows with asset counts, a coverage
-- card and mock twin-badges — every visible judgment needs a plain-language
-- chapter, otherwise a badge on the page has no explanation next to it.
-- Discipline: the dollar-quoted body carries zero table names / config keys /
-- feature codes; idempotent via ON CONFLICT (domain_code, sort_no) DO UPDATE.
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES ('T', '接口清单：契约端点逐条对账资产与覆盖', $mdq$
## 这一页能做什么
这一页回答「我的接口契约里，每一条端点有没有人真正在测」。选定一份接口契约后，页面按该契约最新版本的每一条端点逐行列出：这条端点建了几个测试请求、升格了几条用例、有没有 Mock 覆盖、契约演进后它处于稳定/有变更/已移除哪种状态。顶部覆盖率卡给出汇总数字，「尚无资产」那一格就是待补的缺口清单。入口：左侧菜单「执行测试 → 接口清单」。

## 怎么用
1. 顶栏先选项目——本页列表永远是当前项目的切片，切换项目即切换数据。
2. 在「选择接口契约」下拉里挑一份契约（可输入过滤）；锚定版本=该契约名称链的最新版本，覆盖率卡上会写明锚的是哪份契约的哪个版本。
3. 看行：方法色块+路径（{id} 这类模板变量段高亮）；「契约状态」列说明这条端点在最新版本里的新鲜度；「已建请求」「已升格用例」是真实计数，0 就代表还没有测试资产。
4. 看 Mock 列三种态：灰色「未建 Mock」=没有规则覆盖；绿色「已建 Mock」=有确定规则；紫色「AI 兜底(未验证)」=该端点的 Mock 应答全部来自大模型兜底，没有一条经过确定性规则，不能当真实集成看。
5. 已弃用的端点仍会列出并打灰色「已弃用」标，但不计入覆盖率分母——数字诚实，不藏旧账也不虚高。

## 操作背后系统做了什么
- 清单的每一行、每一个计数都由服务端按契约快照与资产表联查得出，页面只展示不重算；换个浏览器、换个人看同一份契约得到同一个数字。
- 锚定版本取名称链的最大版本号；旧版本行不混进当前清单，历史对照另有版本对照视图负责。
- 「契约状态」三态的含义：稳定=这条端点在版本演进中没变；有变更=它在新版本里改动过（参数或结构），基于旧契约建的请求可能要跟改；已移除=新版本里这条端点没了，存量资产会挂过期提醒。
- 计数口径：请求数按端点契约键归集（模板路径自动对上具体路径的请求）；用例数只统计仍有效的升格用例，已删除的不计。
- 行上的数字与「生成用例」都是入口：点计数徽标直达对应请求、用例、Mock 清单（自动带上本接口路径作过滤词）；点「生成用例」把这条端点的契约摘要注入 AI 生成链。

## 常见问题
- 下拉里没有契约？——该项目还没上传过 OpenAPI 契约；就在本页右上角「上传契约」或「从 URL 拉取」建第一份（需求文档页的契约卡已退役并入本页）。
- 覆盖率为什么不是按全部端点算的？——分母只算最新版本里未弃用的端点；弃用端点列出来是让你看见历史，不计入是让你不被历史拖累。
- 紫色「AI 兜底(未验证)」要去掉怎么办？——给这条端点建一条确定性的 Mock 规则；只要还存在非兜底的应答来源，紫标即消。
- 「已移除」的行还要处理吗？——要：它说明存量请求或用例引用的端点在新契约里已不存在，先确认业务上是否真的下线，再决定清资产还是回退契约版本。
$mdq$,
    3, '2.40', now())
ON CONFLICT (domain_code, sort_no) DO UPDATE
SET title = EXCLUDED.title,
    body_md = EXCLUDED.body_md,
    version = EXCLUDED.version,
    updated_at = now();
