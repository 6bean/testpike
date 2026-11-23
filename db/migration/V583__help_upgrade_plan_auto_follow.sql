-- Migration V583: R61 help-chapter companion for the upgrade-plan list
-- auto-follow shipped in the repayment batch (H-face). The plan
-- progress panel now keeps RUNNING-row statuses fresh through the shared
-- useListAutoFollow beat, so the chapter gains a behaviour section in the
-- same batch. Pure UPDATE append guarded by NOT LIKE on its marker heading:
-- a replay after the amendment writes zero rows. Rows are anchored by
-- domain_code + title prefix (not by id) so the match holds across chain
-- slots whose tp_help_doc ids evolve independently. Applied manually to the
-- dev DB (Flyway not enabled; db/migration is the reference + replay source
-- for schema-init, regenerated via tools/ops/gen_schema_init.sh).

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 升级计划状态自动更新\n'
       || E'点「确认执行」后计划进入「执行中」：升级计划列表会自动更新每一行的状态，列表上方出现「升级计划进行中，列表状态会自动更新」的提示，期间不需要做任何刷新操作，也不用守在页面等待。\n'
       || E'- 计划走到终态（已完成、已终止、已刹车）后提示自动消失，列表停留在执行器侧回传的事实上，此后的状态变化不会再发生。\n'
       || E'- 「已暂停」表示等待人的决定：点「继续」后列表会重新开始自动跟随；「已刹车」的计划在下方步骤明细里逐段查看刹车原因。\n'
       || E'- 切到别的页签再回来，列表按最新状态重新读取；列表上方的「刷新」按钮保留，想立即取数时可以按，但不是必须。\n'
       || E'- 自动更新的间隔可在「系统设置 → 系统配置」中调整，保持默认即可。\n'
 WHERE domain_code = 'K'
   AND title LIKE '升级计划%'
   AND body_md NOT LIKE '%升级计划状态自动更新%';
