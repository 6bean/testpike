-- Migration V582: R61 help-chapter companions for the two list auto-follow
-- pages shipped in the repayment batch — the journey run timeline
-- (F-face) and the report export-task panel (G-face) now keep their statuses
-- fresh without any user action, so both chapters gain a behaviour section in
-- the same batch. Pure UPDATE appends, each guarded by NOT LIKE on its marker
-- heading: a replay after the amendment writes zero rows. Rows are anchored
-- by domain_code + title prefix (not by id) so the match holds across chain
-- slots whose tp_help_doc ids evolve independently. Applied manually to the
-- dev DB (Flyway not enabled; db/migration is the reference + replay source
-- for schema-init, regenerated via tools/ops/gen_schema_init.sh).

-- F-face: the journey orchestration chapter explains the self-updating
-- traffic lights on the run timeline.
UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 旅程执行状态自动更新\n'
       || E'点「发起运行」后打开运行时间线页：这次运行还在推进时，每段红绿灯会自动更新，运行标题旁出现「旅程正在执行，段状态会自动更新」的提示，说明页面正在实时跟随，期间不需要做任何刷新操作。\n'
       || E'- 每段都进入终态（成功、失败或跳过）后提示自动消失，红绿灯停在执行链路回传的事实上，此后的红绿灯变化不会再发生。\n'
       || E'- 关闭时间线页不影响运行本身：重新进入页面时按最新状态展示；页顶的「刷新」按钮保留，想立即取数时可以按，但不是必须。\n'
       || E'- 自动更新的间隔可在「系统设置 → 系统配置」中调整，保持默认即可。\n'
       || E'- 某一段发起失败时只点名这一段，其余段的红绿灯照常推进，失败原因点进对应批次详情查看。\n'
 WHERE domain_code = 'I'
   AND title LIKE '旅程编排%'
   AND body_md NOT LIKE '%旅程执行状态自动更新%';

-- G-face: the statistics report chapter explains the self-updating export
-- task rows and the download affordance that appears on completion.
UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 导出任务状态自动更新\n'
       || E'提交报表导出后，「导出任务」列表新增一行，行状态从「处理中」走到「完成」或「失败」会自动更新，列表上方出现「导出任务进行中，状态会自动更新」的提示；所有任务都出结果后提示自动消失——提交后不需要刷新，也不需要留在页面等待。\n'
       || E'- 只有「完成」的行出现「下载」按钮，文件生成后随时点击取走；「失败」的行调整报表类型或时间窗后重新提交即可。\n'
       || E'- 导出完成会同步发送一条站内信，回到本页面就能看到任务行的最新状态。\n'
       || E'- 离开页面再回来时，列表按最新状态重新读取，已完成任务的下载入口照常可用。\n'
       || E'- 自动更新的间隔可在「系统设置 → 系统配置」中调整，保持默认即可。\n'
 WHERE domain_code = 'Q'
   AND title LIKE '统计报表%'
   AND body_md NOT LIKE '%导出任务状态自动更新%';
