-- Migration: second chapter of the AI-evaluation help domain (Y|2) — the
-- evaluation-set page door. Four-section structure (what this page can do /
-- how to use it / what the system does behind the actions / common questions).
-- Discipline: body carries no feature-point codes, task numbers, table names or
-- config keys (help-doc scanner gate). Idempotent via ON CONFLICT DO UPDATE;
-- sort_no 2 taken from the live Y-domain scan (Y|1=V213 report chapter);
-- version ladder continues 2.99 -> 3.00.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -f - < db/migration/V534__help_doc_evalset_chapter.sql
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('Y', '评测集与造题：从零建一轮 AI 评测', $mdq$,
## 这一页能做什么
评测集是给 AI 裁判用的「考卷」：一套带标准答案的样本，按名称和版本管理。这一页让你看清每套考卷里有什么题（样本清单）、考过哪些版本（版本谱系），并说明样本都从哪里来。跑一轮 AI 评测的完整动线是：先有评测集 → 发起评测 → 在评测报告页看结果。

## 怎么用（按操作顺序）
1. **选一套考卷**：页面上方选择评测集名称（下拉候选来自已有评测运行中出现过的名称，也可以直接输入），再填版本号，点「查询样本」。
2. **看题目**：下方表格逐条列出该版本的全部样本——问题输入、期望输出、来源、创建时间等；条数多时用表格上方的即时搜索按关键词过滤，翻页用底部分页控件。
3. **认准版本**：版本号越大越新。发布新版本不会覆盖旧版本，历史评测引用的旧版本样本永远原样可查。
4. **看来源**：每条样本标着它是怎么进来的——批次执行记录晋升、已采纳用例晋升、训练样本晋升或 AI 造题；晋升与造题的发起入口在本页逐步补齐，补齐前可先在对应业务页操作。

## 操作背后系统做了什么
- 样本清单读的是评测集的版本快照：你看到的输入与期望输出就是当时发布定格的内容，之后源用例怎么改都不会悄悄改变已有版本。
- 版本递增、快照摘要、晋升资格判定（比如批次记录必须是终态、采纳用例要过入选闸）全部在服务端单点完成，这一页只如实呈现结果，不做第二套计算。
- 敏感标记为「是」的样本会在后续评测出报告时提示注意脱敏，避免把敏感内容带进对外产物。

## 常见问题
- 下拉里没有我的评测集名？——下拉候选来自历史评测运行；从未跑过评测的新集可以直接在输入框里填名称，或先用晋升入口建出第一版。
- 查询提示集或版本不存在？——版本号必须是该集已发布的版本；填错会被明确拒绝，不会返回一个空列表让你误以为集是空的。
- 样本清单为空但版本存在？——该版本发布时就没有样本；用「查询样本」换一个更大的版本号看看最新版本。
$mdq$, 2, '3.00')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET
  title = EXCLUDED.title,
  body_md = EXCLUDED.body_md,
  version = EXCLUDED.version,
  updated_at = now();
