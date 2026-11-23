-- Migration V577: R61 help-chapter amendment for the interface-document
-- import wizard . Channel-B AI
-- extraction no longer polls with a reopen-later hint: the dispatched run
-- streams live frames into a fixed-height window, seals itself on the
-- terminal frame and opens the endpoint preview automatically, so the
-- chapter describing this page gains the section in the same batch.
-- Idempotent: the append is guarded by NOT LIKE on its marker heading, a
-- replay after the amendment writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 发起 AI 抽取后怎么看过程\n'
       || E'在「接口设计文档（AI 抽取）」页签选好文档、点击「发起 AI 抽取」后，抽取过程直接在页面下方的「过程详情（实时）」窗口逐帧展示：已发起、第几组完成、完成或失败都实时可见，不需要刷新页面，也不需要重新打开向导等待。\n'
       || E'- 确认消耗弹窗只是让你确认用量预估：点「取消」也不会停止已经派发的抽取，过程窗口照常展示直至自动进入接口预览。\n'
       || E'- 窗口高度固定，新事件从底部流入并自动跟随；向上滚动回看时自动暂停跟随，点「跳到最新」恢复。保留帧数有上限，最早的过程帧自动清掉，最终结果以接口预览为准。\n'
       || E'- 一段时间没有新事件时窗口会显示心跳行，说明任务仍在进行；抽取完成时窗口自动收起，接口清单预览自动打开，低置信条目默认不勾选，由你勾选后才写入。\n'
       || E'- 抽取失败时页面直接写明原因（如文档中未识别到接口定义、模型调用超时等）；按原因处理后，可在「模型提示」输入框补充要求，再次点击「发起 AI 抽取」获取最新结果。\n'
  WHERE id = 57
    AND body_md NOT LIKE '%发起 AI 抽取后怎么看过程%';
