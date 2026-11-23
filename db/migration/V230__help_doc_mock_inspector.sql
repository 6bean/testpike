-- Migration: open the API-mocking help chapter for the traffic inspector page
-- (red-line 8 doc sync; consumer = mock-inspector page help anchor openHelp('T')).
-- What this segment lands and why: domain T (API testing) previously carried a single
-- chapter, so the inspector leaf could not anchor-jump to its own explanation — a
-- domain-code jump only ever hit the first chapter. This chapter describes what the
-- inspector answers (which rule version the serving plane is live with, and why a
-- request hit or missed), matching the shipped UI one-to-one.
-- Discipline: the $mdq$ body carries zero table names / config keys / feature codes;
-- idempotent via ON CONFLICT (domain_code, sort_no) DO UPDATE.
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('T', '接口仿真：Mock 流量怎么看清命中与落空', $mdq$
## 这一页能做什么
流量检视回答两个问题：「数据面此刻在服务哪个版本的规则」和「每一个真实进来的请求为什么命中/落空了这条规则」。选定项目和 Mock 空间后，页面顶部是一条装载状态条，下方是实时流量账本：每行一个到达过的请求，写明方法、路径、命中结果、对上的规则名、耗时与故障注入标记；点任意一行展开请求快照与匹配轨迹。

## 怎么用
入口：左侧菜单「接口仿真 → 流量检视」。
1. 顶部选择项目，随即选择该项目下的 Mock 空间（空间在规则页创建并发布后才会出现在这里）。
2. 状态条绿=数据面正在按最新版规则对外服务；黄=有降级（新版本还没装载上、装载连续失败、或账本缓冲溢出），条上直接写明原因；红=此刻读不到数据面自检，这是如实上报，不等于规则丢了。
3. 「全部流量 / 只命中 / 只落空」三档筛选排掉噪音：排查「我的规则怎么没生效」直接切「只落空」。
4. 「实时刷新」勾选后每 2 秒拉一次新账本，取消勾选即停止轮询；离开页面自动停止。
5. 点任意一行打开右侧抽屉：请求快照（方法/路径/查询参数/请求头/请求体）+ 按规则声明序逐条判定的匹配轨迹，写明每条候选规则为什么被排除。

## 操作背后系统做了什么
- 装载状态条是现场读取数据面自检的结果，页面不缓存也不猜：读不到就明说读不到，绝不拿上一次的值冒充当前健康。
- 数据面装载新版本失败时，仍以上一次成功的版本继续对外服务（旧值不冒充新值，但也不至于停服），状态条把「连续失败几次、最近一次什么原因」原样呈现。
- 规则发布后数据面未及时装载上（状态条黄），期间进来的请求走的还是旧规则——匹配轨迹里能看到实际参与判定的候选集，不会用新规则解释旧行为。
- 账本写入走异步攒批：在途缓冲条数、是否已降级落盘、溢出丢弃条数都在状态条如实声明。丢弃只意味着账本有缺口，不意味着请求失败。
- 请求快照里的敏感内容在进入页面之前就已由管道脱敏，检视器只渲染管道给出的值，页面上不存在第二次「解开看原文」的路径。
- 空间按路由优先级排序展示；一个空间对应一个数据面实例，切换空间即切换对应的装载自检与流量账本。

## 常见问题
- 选了项目却没有空间可选？——该项目还没建 Mock 空间，去接口仿真的规则页创建并发布一个。
- 状态条一直红？——数据面此刻不可达（进程没起来或地址配置指向了别处），先确认服务在线，页面不会假装健康。
- 流量列表是空的？——这个空间最近确实没有请求到达；检视器只呈现真实到过的流量，不生成示例数据。
- 明明配了规则却显示落空？——切「只落空」并展开该行匹配轨迹，逐条看候选规则被排除的原因（路径、方法、参数匹配条件与声明顺序都会影响结论）。
- 账本说丢弃了若干条，会影响判定吗？——丢弃的是账本记录，不是请求本身；要精确统计请先降低流量或调大缓冲。
$mdq$, 2, '1.0')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET title = EXCLUDED.title, body_md = EXCLUDED.body_md, version = EXCLUDED.version, updated_at = now();
