-- Help chapter for the journey orchestration menu leaf.
-- carried no chapter whose title contains that keyword, so the drawer silently fell back
-- to the first chapter (fake coverage per the per-leaf anchor contract). This chapter is
-- the anchor target: the drawer matches the anchor against chapter titles in the domain.
-- Discipline: body carries zero table names / config keys / feature codes; idempotent via
-- ON CONFLICT (domain_code, sort_no) DO UPDATE. Mirrors V251/V252/V254.
-- Usage: apply with psql against the chain's own database (Flyway is not enabled).

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES
('I', '旅程编排：跨项目的顺序段表与全链红绿灯', $mdq$
## 这一页能做什么
在一个项目集下，把多个项目里已发布的资产和用例排成一张顺序段表，形成一条跨项目的旅程计划。发起运行后，每一段自动展开为它所属项目的执行批次；全部段跑完，红绿灯和准出签字在同一条时间线页上跟踪。

## 怎么用
1. 顶栏切到目标项目集，左侧「执行」分组的「旅程编排」进入旅程清单，可按名称搜索、分页浏览。
2. 新建旅程后进入段表编辑页：逐段选择所属项目与该项目已发布的资产（或用例集），拖动或用上下按钮调整段的先后顺序。
3. 段表就绪后点「发起运行」：系统按段顺序展开批次，某一段前置条件不满足时只点名这一段，其余段照常发起。
4. 运行期间打开时间线页：每段一盏红绿灯（等待/进行中/成功/失败/跳过），失败段可直接点进对应批次详情看原因。
5. 全部段进入终态后，旅程负责人可在时间线页做一次准出签字；签字权只属于旅程负责人，其他人操作会被拒绝并提示负责人是谁。

## 操作背后系统做了什么
段的引用钉住在编辑时刻选定的已发布资产版本：资产改版后旧钉不会自动跟随，需要先在段上摘除旧引用再重新钉新版。展开出的批次数据归属仍留在各自项目里，旅程只是编排视图，不搬动任何项目的数据。每段批次的终态由执行链路回传，时间线页读到的就是回传后的事实，红绿灯不做二次推断。

## 常见问题
- 为什么有的段显示跳过？——它前面的段发起失败时，后续段不再展开，统一标为跳过，失败原因在点名的那一段上。
- 资产发布方撤下了资产怎么办？——已发起的运行不受影响；未发起的段会提示引用失效，换钉当前已发布版本即可。
- 签字后可以重签吗？——不可以，一次运行只签一次，页面上能看到签署人和签署时间。
$mdq$,
7, '2.97', now())
ON CONFLICT (domain_code, sort_no) DO UPDATE
SET title = EXCLUDED.title,
    body_md = EXCLUDED.body_md,
    version = EXCLUDED.version,
    updated_at = EXCLUDED.updated_at;
