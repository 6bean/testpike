-- Migration: third chapter of the AI-evaluation help domain (Y|3) — the
-- run-history page door (start an evaluation over a frozen set version and
-- a rubric, dispatch pending runs, jump to the report).
-- Four-section structure (what this page can do / how to use it / what the
-- system does behind the actions / common questions).
-- Discipline: body carries no feature-point codes, task numbers, table names
-- or config keys (help-doc scanner gate). Idempotent via ON CONFLICT DO
-- UPDATE; sort_no 3 from the live Y-domain scan (Y|1=V213 report chapter,
-- Y|2=V492 evalset chapter); version ladder continues 3.02 -> 3.03.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -f - < db/migration/V498__help_doc_judge_runs_chapter.sql
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('Y', '发起评测与运行历史：把考卷跑起来', $mdq$,
## 这一页能做什么
评测运行是「把一套考卷交给 AI 裁判打分」的那一步。这一页让你用已发布的评测集版本和裁判标准发起一轮评测、派发待跑的运进队列、跟踪每次运行的状态（待派发 / 进行中 / 已完成 / 失败），并从任意一行直达对应的评测报告。完整动线：评测集页先有考卷 → 本页发起并派发 → 评测报告页看结果。

## 怎么用（按操作顺序）
1. **发起评测**：点「发起评测」，依次选三样——评测集版本（下拉候选来自已发布版本，标注题目数）、裁判标准（同名标准只列最新版本）、被评目标（自家生成配置填配置 ID，被测 AI 系统填系统标识）。三样选齐才能提交，缺哪一项页面上会写明。
2. **派发运**：新建的运行是「待派发」状态，点行上的「派发评测」把它送进评测队列；派发回执会告诉你送了多少条样本、由哪个裁判提供方执行。
3. **看状态**：状态灯按运行进度更新——待派发（灰）、进行中（黄）、已完成（绿）、失败（红）。列表按创建时间从新到旧分页，可按评测集名称或状态筛选。
4. **看报告**：点任意一行（或行上「查看报告」）跳到评测报告页，自动带上这次运行的编号。

## 操作背后系统做了什么
- 创建运行时会把评测集版本与裁判标准定格为本次依据：之后考卷再改版、标准再更新，历史运行引用的仍是当时的内容，报告永远说得清「模型变好还是题变水」。
- 版本是否存在、快照是否对得上、派发资格是否满足，都在服务端统一校验；校验不过会给出具体原因，页面原样提示，不会自行改写。
- 派发后由 AI 裁判逐条样本打分并回写结果，状态随进度从进行中转入已完成或失败；失败原因在报告页与运行行上可查。

## 常见问题
- 评测集版本下拉是空的？——先回到评测集页发布第一个版本（手工添加、晋升或 AI 造题都可以）。
- 被评目标引用被拒绝？——自家配置填生成配置 ID，外部系统填系统标识；具体不合规原因提示里会写明。
- 运行显示已完成但报告没数据？——报告读取在评测报告页完成；若派发回执显示 0 条，说明该运行没有样本进入队列，请检查评测集版本是否为空。
$mdq$, 3, '3.03')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET
  title = EXCLUDED.title,
  body_md = EXCLUDED.body_md,
  version = EXCLUDED.version,
  updated_at = now();
