-- Migration: honest guidance update for the AI-evaluation help domain (R18
-- shipped-page reconciliation). The eval-set page now carries all five sample
-- creation doors (manual / adopted-case promotion / training-sample promotion /
-- batch-result promotion / question-generation wizard) and the run-history
-- page is live, so the Y|2 chapter drops the outdated "entries will be added
-- later" sentence and describes the real buttons; the Y|3 chapter fixes two
-- typos in the dispatch wording.
-- Discipline: body carries no feature-point codes, task numbers, table names
-- or config keys (help-doc scanner gate). Idempotent via ON CONFLICT DO UPDATE
-- at the same (domain_code, sort_no); version ladders 3.01 -> 3.02 (Y|2) and
-- 3.03 -> 3.04 (Y|3).
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike_slot6 -f - < db/migration/V499__help_doc_eval_doors_honest_guidance.sql
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('Y', '评测集与造题：从零建一轮 AI 评测', $mdq$,
## 这一页能做什么
评测集是给 AI 裁判用的「考卷」：一套带标准答案的样本，按名称和版本管理。这一页让你看清每套考卷里有什么题（样本清单）、考过哪些版本（版本谱系），并提供五种建卷入口：手工添加、采纳晋升、训练样本晋升、批次晋升、AI 造题。跑一轮 AI 评测的完整动线是：先有评测集 → 在「评测运行」页发起评测 → 在评测报告页看结果。

## 怎么用（按操作顺序）
1. **建考卷**：筛选栏右侧的五个入口都在本页——点「手工添加」逐行录入问题与期望输出；点「采纳晋升」把当前项目里已采纳的用例挑进来；点「训练样本晋升」从训练样本中勾选条目晋升；点「批次晋升」把批次执行结果挑进来；点「AI 造题」走两步向导：选文档 → 预览候选题与预计成本 → 勾选要保留的题目发布。发布时当前版本已有样本会一并保留、整体定格为新版本。各入口受权限控制，未授权时按钮不会出现。
2. **选一套考卷**：页面上方选择评测集名称（下拉候选来自已有评测运行中出现过的名称，也可以直接输入），再填版本号，点「查询样本」。
3. **看题目**：下方表格逐条列出该版本的全部样本——问题输入、期望输出、来源、创建时间等；条数多时用表格上方的即时搜索按关键词过滤，翻页用底部分页控件。
4. **认准版本**：版本号越大越新。发布新版本不会覆盖旧版本，历史评测引用的旧版本样本永远原样可查。
5. **看来源**：每条样本标着它是怎么进来的——手工添加、批次执行记录晋升、训练样本晋升或 AI 造题。
6. **去跑评测**：版本发布后，到「评测运行」页选定这套版本与裁判标准发起评测，页面会引导你派发和跟踪状态。

## 操作背后系统做了什么
- 晋升与发布都在服务端完成资格判定与版本定格：同一次操作产出一个全新版本号并留下快照摘要，已有版本不被改写；样本不合规矩（来源不明、超长等）会被直接拒绝并说明原因，不会静默入库。
- AI 造题的预览只展示候选题与预计成本，不写入评测集；勾选子集并确认发布后才会产出新版本，未勾选的题目不会入库。
- 敏感标记为「是」的样本会在后续评测出报告时提示注意脱敏，避免把敏感内容带进对外产物。
- 采纳用例晋升走的是服务端入选判定，不满足条件的用例不会被悄悄塞进新版本。

## 常见问题
- 下拉里没有我的评测集名？——下拉候选来自历史评测运行；从未跑过评测的新集可以在发布对话框里直接输入新名称，第一个版本由发布动作建出来。
- 造题向导预览后没有发布？——预览不落库；勾选要保留的题目并确认发布，才会产出新版本。造题需要「AI 造题」权限，未授权时入口不会出现。
- 查询提示集或版本不存在？——版本号必须是该集已发布的版本；填错会被明确拒绝，不会返回一个空列表让你误以为集是空的。
- 样本清单为空但版本存在？——该版本发布时就没有样本；用「查询样本」换一个更大的版本号看看最新版本。
- 发布按钮点不动？——手工添加要先填至少一行完整样本（问题和期望输出都不能空）；晋升类入口要先勾选至少一条。
$mdq$, 2, '3.02')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET
  title = EXCLUDED.title,
  body_md = EXCLUDED.body_md,
  version = EXCLUDED.version,
  updated_at = now();

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('Y', '发起评测与运行历史：把考卷跑起来', $mdq$,
## 这一页能做什么
评测运行是「把一套考卷交给 AI 裁判打分」的那一步。这一页让你用已发布的评测集版本和裁判标准发起一轮评测、把待派发的运行送进评测队列、跟踪每次运行的状态（待派发 / 进行中 / 已完成 / 失败），并从任意一行直达对应的评测报告。完整动线：评测集页先有考卷 → 本页发起并派发 → 评测报告页看结果。

## 怎么用（按操作顺序）
1. **发起评测**：点「发起评测」，依次选三样——评测集版本（下拉候选来自已发布版本，标注题目数）、裁判标准（同名标准只列最新版本）、被评目标（自家生成配置填配置 ID，被测 AI 系统填系统标识）。三样选齐才能提交，缺哪一项页面上会写明。
2. **派发运行**：新建的运行是「待派发」状态，点行上的「派发评测」把它送进评测队列；派发回执会告诉你送了多少条样本、由哪个裁判提供方执行。
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
$mdq$, 3, '3.04')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET
  title = EXCLUDED.title,
  body_md = EXCLUDED.body_md,
  version = EXCLUDED.version,
  updated_at = now();
