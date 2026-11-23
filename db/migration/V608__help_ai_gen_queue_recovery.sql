-- Migration V608: WS batch help-surface sync (user report 2026-11-19).
-- The AI generation page now re-attaches the running queue after a page
-- leave (server ledger is the source), the requirement list marks points
-- whose cases are generating, and both panels fill the content band with
-- internal scrolling. Sections append idempotently (NOT LIKE guard = zero
-- writes on replay), same posture as the V539 precedent.
-- Renumbered from V597, which collided with the already-applied help-doc
-- depth wave 8; the task code is dropped from the file name per project
-- naming rules (traceability lives in the WBS row and the report file).
--
-- The chapter text stays user-facing (R61): no internal governance words;
-- wording matches the on-screen labels (queue re-attach / "Case
-- generating" marker / in-panel scrolling).

UPDATE tp_help_doc
   SET body_md = body_md || '

## 提交后可随时回来查看

批量生成提交后不用守着页面：离开再回来，本页会自动带回当前项目的队列与进度，
每一行的状态（排队中/生成中/已完成/失败/已停止）保持如实——完成行可「查看用例」，
失败行可单条「重试」，进行中的行可直接「停止」。左侧需求填写区与右侧进度面板
各占满一屏高度，长队列在面板内滚动，页面整体不会变长。正在生成用例的需求点，
在「需求点管理」页会带上「用例生成中」标记，点标记即直达对应队列。'
 WHERE domain_code = 'A' AND title = '从需求一键生成结构化用例'
   AND body_md NOT LIKE '%提交后可随时回来查看%';

UPDATE tp_help_doc
   SET body_md = body_md || '

- 怎么知道哪个需求点的用例正在生成？——发起生成后，本页该需求点行会带上
  「用例排队中 / 用例生成中」标记，点标记直达 AI 生成页的对应队列；生成结束
  标记自动消失，无需手动刷新。'
 WHERE domain_code = 'F' AND title = '上传文档，自动提取需求点'
   AND body_md NOT LIKE '%用例正在生成%';
