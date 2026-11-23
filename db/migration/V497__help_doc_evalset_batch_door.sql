-- Migration: the batch-result promotion door shipped on the evaluation-set
-- page (fourth on-page build door: manual add / adopted promotion /
-- training-sample promotion / batch promotion). The Y|2 chapter now lists all
-- four doors, folds the batch flow into the usage steps and the guarded-
-- button FAQ, and narrows the remaining "entry to be added later" sentence to
-- the question-gen wizard only (honest guidance red line: mention only doors
-- that exist). Idempotent via ON CONFLICT DO UPDATE at the same
-- (domain_code, sort_no); version ladder 3.01 -> 3.02.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike_slot6 -f - < db/migration/V497__help_doc_evalset_batch_door.sql
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('Y', '评测集与造题：从零建一轮 AI 评测', $mdq$,
## 这一页能做什么
评测集是给 AI 裁判用的「考卷」：一套带标准答案的样本，按名称和版本管理。这一页让你看清每套考卷里有什么题（样本清单）、考过哪些版本（版本谱系），并提供四种建卷入口：手工添加样本、已采纳用例晋升、训练样本晋升、批次执行晋升。跑一轮 AI 评测的完整动线是：先有评测集 → 发起评测 → 在评测报告页看结果。

## 怎么用（按操作顺序）
1. **建考卷**：筛选栏右侧点「手工添加」逐行录入问题与期望输出，发布时当前版本已有样本会一并保留、整体定格为新版本；点「采纳晋升」把当前项目里已采纳的用例挑进来成新版本；点「训练样本晋升」从训练样本中勾选条目晋升；点「批次晋升」选择一个执行批次，勾选其中失败或超时的执行任务晋升成新版本。四个入口都需要「评测集管理」权限，未授权时按钮不会出现。
2. **选一套考卷**：页面上方选择评测集名称（下拉候选来自已有评测运行中出现过的名称，也可以直接输入），再填版本号，点「查询样本」。
3. **看题目**：下方表格逐条列出该版本的全部样本——问题输入、期望输出、来源、创建时间等；条数多时用表格上方的即时搜索按关键词过滤，翻页用底部分页控件。
4. **认准版本**：版本号越大越新。发布新版本不会覆盖旧版本，历史评测引用的旧版本样本永远原样可查。
5. **看来源**：每条样本标着它是怎么进来的——手工添加、批次执行记录晋升、训练样本晋升或 AI 造题；AI 造题向导的发起入口在本页后续补齐，补齐前可先在知识文档页操作相关能力。

## 操作背后系统做了什么
- 晋升与发布都在服务端完成资格判定与版本定格：同一次操作产出一个全新版本号并留下快照摘要，已有版本不被改写；样本不合规矩（来源不明、超长等）会被直接拒绝并说明原因，不会静默入库。
- 批次晋升只收失败或超时的执行任务：服务端会逐条复核任务状态，批次里刚跑完或还没结束的任务混进来时整批操作会被拒绝并点名是哪条任务，不会出现晋升了一半的版本。
- 敏感标记为「是」的样本会在后续评测出报告时提示注意脱敏，避免把敏感内容带进对外产物。
- 采纳用例晋升走的是服务端入选判定，不满足条件的用例不会被悄悄塞进新版本。

## 常见问题
- 下拉里没有我的评测集名？——下拉候选来自历史评测运行；从未跑过评测的新集可以在发布对话框里直接输入新名称，第一个版本由发布动作建出来。
- 查询提示集或版本不存在？——版本号必须是该集已发布的版本；填错会被明确拒绝，不会返回一个空列表让你误以为集是空的。
- 样本清单为空但版本存在？——该版本发布时就没有样本；用「查询样本」换一个更大的版本号看看最新版本。
- 发布按钮点不动？——手工添加要先填至少一行完整样本（问题和期望输出都不能空）；三种晋升要先勾选至少一条；批次晋升还要先选好批次，选中的批次没有失败或超时的任务时页面会直接说明。
- 批次下拉里没有我要的批次？——下拉列出当前项目的批次（最近 200 条，支持按名称搜索）；批次属于别的项目时先切换顶栏项目再打开对话框。
$mdq$, 2, '3.02')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET
  title = EXCLUDED.title,
  body_md = EXCLUDED.body_md,
  version = EXCLUDED.version,
  updated_at = now();
