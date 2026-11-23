-- Help chapter for the contract change-analysis review queue leaf .
-- Why: the menu leaf '/api-spec-change-analysis' (domain T, multi-chapter) needs
-- its own chapter — without one its "?" would land on another chapter and the
-- per-leaf anchor contract (menuHelpCoverage) would be fake coverage.
-- Discipline: body carries zero table names / config keys / feature codes;
-- idempotent via ON CONFLICT (domain_code, sort_no) DO UPDATE. Mirrors V251/V252.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES
('T', '变更分析：契约版本逐条 diff 的复核队列', $mdq$
## 这一页能做什么
同一份接口契约每次升版本，系统自动和上一版逐条对比：新增了哪些接口、哪些接口的参数或结构变了、哪些接口被删了。本页就是这些差异的复核队列——每条差异关联到具体的请求、用例、Mock 仿真条目，看完留一条处置备注结束复核。

## 怎么用
1. 顶栏选项目，本页即该项目全部契约版本的复核队列（待复核/已处置可筛）。
2. 点行首箭头展开：逐条看端点变更和它关联的请求/用例/Mock 数量。
3. 「处置」写备注（必填）标记已复核；可勾选多条一起批量处置，一次备注留痕。
4. 新增接口还没有测试资产？展开行里有直达「接口清单」的建议动作；接口被删了？关联用例先去用例页人工圈选做回归——本页不会自动建回归批，人工确认是硬规矩。

## 操作背后系统做了什么
版本对比在上传/拉取/订阅扫描三个入口同点触发，差异与影响面在服务端算好并一次定格，本页只读事实不重算。队列有「待复核 N」计数与站内信提醒；接口清单页的徽标能直接深链到对应版本的分析行。处置只改这条复核记录自身的状态，不动任何用例状态机。

## 常见问题
- 为什么处置要写备注？——这是审阅留痕，事后能回答「当时看过、结论是什么」。
- 处置了用例为什么还红着？——处置只结束本条差异的复核；用例的失效标注与回归圈选在各自页面按人工确认走，两套状态各管各的。
- 同一对版本会重复出现吗？——不会，同版本对重复触发只刷新差异内容。
$mdq$,
9, '2.92', now())
ON CONFLICT (domain_code, sort_no) DO UPDATE
  SET title = EXCLUDED.title,
      body_md = EXCLUDED.body_md,
      version = EXCLUDED.version,
      updated_at = now();
