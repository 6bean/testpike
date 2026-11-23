-- Migration V576: R61 help-chapter amendment for the batch execution page
-- . Batch start no longer relies on
-- manual page refresh: the header badge, the fixed-height live event window with
-- follow/pause/back-to-bottom, heartbeat frames while quiet, and the automatic
-- terminal sealing with silent list re-read are new on-screen behaviour, so the
-- chapter must describe them in the same batch as the wiring ships.
-- Idempotent: the append is guarded by NOT LIKE on its marker heading, a replay
-- after the amendment writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 运行中怎么看过程，不用手动刷新\n'
       || E'发起批次执行后，页面顶部会出现「过程进行中」状态标记并累计事件数；运行进度不需要你刷新页面去等。\n'
       || E'- 点击该标记展开「过程详情（实时）」子窗：执行事件从底部不断流入并自动跟随最新一帧；向上滚动回看历史时自动暂停跟随（提示「已暂停跟随，正在回看历史」），点「跳到最新」即恢复跟随。子窗高度固定，不会随内容变长；保留有上限，最早的帧自动清掉，完整结果始终以列表和报告为准。\n'
       || E'- 一段时间没有新事件时，子窗会显示心跳行「过程仍在进行，近 N 秒暂无新内容」，说明任务还活着；事件流断开时结尾显示「过程详情到此中断，最新结果可在列表查看」，不会出现空白假死。\n'
       || E'- 批次到达终态（完成、失败或取消）后子窗自动收起，批次详情和用例列表在后台静默重新加载最新结果，全程无需手动刷新。\n'
  WHERE id = 9
    AND body_md NOT LIKE '%运行中怎么看过程，不用手动刷新%';
