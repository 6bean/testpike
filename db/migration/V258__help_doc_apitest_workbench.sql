-- Migration: rewrite the API-test help chapter for the rebuilt workbench IA.
-- Why: the page moved from three stacked dropdowns to a two-list rail + request
-- UI vocabulary; the old chapter still described the retired navigation and must
-- not mislead users arriving from the leaf "?" anchor.
-- preserves it as its leading segment so the jump stays green.
-- Discipline: bodies carry zero table names / config keys / feature codes;
-- idempotent via ON CONFLICT (domain_code, sort_no) DO UPDATE.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -v ON_ERROR_STOP=1 -f - < db/migration/V258__help_doc_apitest_workbench.sql

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES
('T', '接口测试：把 HTTP 接口收进接口集，浏览、单发、整集回归', $mdq$
## 这一页能做什么
日常验证后端接口（登录拿令牌、下单、查询返回对不对）不用开着命令行工具一条条敲。这一页把 HTTP 接口像浏览器收藏夹一样收进「接口集」：左栏两级列表浏览管理，右区工作区编辑与发送；单条随时发起看结果，多条能整集发起回归，跑完的响应自动留档，方便比对「这次和上次返回一样吗」。入口：左侧菜单「执行测试 → 接口测试」。

## 页面怎么摆（对应操作动线）
1. **顶栏选项目**：本页列表=当前项目的切片，页顶只读回显归属项目，不用再选一次。
2. **左栏上半=接口集列表**：每个接口集一张卡，显示名称+「N 个接口」计数徽标；集名旁边的即时搜索直接过滤。卡行右上的「更多」收纳改名/删除，新建按钮在区块右上。
3. **左栏下半=请求列表**：选中一个接口集后列出它名下的接口，每行是方法徽标+名称+路径，契约变更/Mock 待复核的接口行上有提醒标；同样带即时搜索。
4. **右区=请求工作区**：点左栏一行进来编辑与发送；「升格为回归用例」「另存为压测场景」「删除」收在工作区头部动作组，不再和浏览控件混排。
5. **项目级入口收在「数据源」菜单**（左栏顶部）：契约导入、契约订阅、在仿真中查看、直建 Mock 桩四条来路都在这里，选完自动刷新左栏。

## 接口集是什么（一句话）
接口集=一组接口的容器：可浏览管理、可整体发起回归，变量池在集内流转。它不是流程编排——「整集运行」按列表顺序发起并把前提取值传给后续请求；需要条件分支的编排请用用例域。

## 怎么用（按操作顺序）
1. **建接口集**：左栏「新建接口集」（如「订单服务冒烟」），权限跟着项目走；支持一层子集分组。
2. **存请求**：选中接口集后新建请求——选方法（GET/POST 等）、填路径（只填 / 开头段，域名由所选环境档案白名单决定）、按需加请求头、参数与断言，保存后可随时编辑。地址前缀与鉴权头可配成集内变量，切环境只改一处。
3. **单发看结果**：工作区点「发送」当场看状态码、耗时和响应体；断言写在请求上（如「状态码必须是 200」「返回里必须有订单号」），不满足直接标红。
4. **整集回归**：接口集卡上的运行入口发起整集，弹窗先给执行顺序与变量依赖预览，再选环境、失败策略发起；逐条给出通过/失败与耗时。
5. **结果留档**：每次运行都留一份记录，「运行记录」里回看每条的完整响应，回归对比不用手动截图。

## 操作背后系统做了什么
- 请求发出前服务端核对你对该接口集所属项目的访问权限；别人项目里的集你既看不到也发不了，杜绝跨项目误发。
- 整集运行由执行侧统一排队：请求按预览确认的顺序发出，单条超时不影响整批，失败条带完整响应体落库。
- 变量替换发生在发送前的最后一刻，同一份请求配置在不同环境不用改动内容，只换变量值。

## 常见问题
- 左栏是空的？——顶栏还没选项目，或该项目还没有接口集；先顶栏选项目，再「新建接口集」或从「数据源 → 契约导入」灌入。
- 请求发送后一直转圈？——多半是目标地址本机不可达或服务未启动；先核对环境档案与路径，再用浏览器验证同一个地址通不通。
- 断言红了但接口其实没报错？——断言按你写的规则判，规则本身写错也会红；点开该条响应详情核对返回值形状再改断言。
$mdq$,
1, '2.99', now())
ON CONFLICT (domain_code, sort_no) DO UPDATE
SET title = EXCLUDED.title,
    body_md = EXCLUDED.body_md,
    version = EXCLUDED.version,
    updated_at = now();
