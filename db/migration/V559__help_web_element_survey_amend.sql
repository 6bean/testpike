-- Migration V559: R61 help-chapter amendment for the manual page survey and
-- the whole-page candidate approval on the Page Element Library page
-- Idempotent: the append is guarded by IS DISTINCT FROM on the marker text,
-- replay after the amendment writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 整页测绘与批量批准（新增能力）\n'
       || E'不想逐个登记元素时，点筛选带右侧的「测绘此页」：填写页面完整 URL 并点「开始测绘」，系统打开该页面一次采齐可见控件，自动生成业务化名称（如「登录按钮」）与候选定位锚。测绘在后台分批进行，弹窗提示「测绘任务已发起（会话 N）」，点「刷新」即可看到候选条目陆续进入列表。\n'
       || E'- 候选锚攒多了逐条点太慢：展开页面行，头部显示「本页还有 N 条候选等待批准」，点「全部批准入库」一键把这一页的候选升为已确认；每一条仍逐条走确认版本校验并留一笔审计事件，任何一条失败会点名成功与失败数量，不会假装全部成功。\n'
       || E'- 视口档：环境档案声明了 mobile/tablet/desktop 档位时，测绘按档逐档重采并合并成跨视口变体锚；未声明档位只采默认视口，不猜测。\n'
       || E'- 未选项目时按钮不可用；页面地址留空会被拒绝并说明需要先填写完整 URL。\n'
       || E'- 成本透明：每次测绘的命名请求都会记入 AI 调用日志（独立功能标签），在用量页可查，不存在看不见的外呼。\n'
  WHERE domain_code = 'F'
    AND title = '页面元素库：Web 定位锚集中建档'
    AND body_md NOT LIKE '%整页测绘与批量批准（新增能力）%';
