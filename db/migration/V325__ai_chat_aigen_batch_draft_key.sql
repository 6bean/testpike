-- Factory switch for AI case-generation batch draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose queueing a batch of requirements for AI case
--   generation with the aiGen.batch.submit write tool. Nothing runs until the
--   human confirms the card through the shared draft door, and the confirmed
--   write runs the SAME AiBatchService.createBatch + per-item MQ publish chain
--   as the generation page's batch dialog — the requirement list validator
--   (non-empty lines, the per-line length ceiling, the hot-updatable
--   ai.batch.max-count batch ceiling) re-decides at landing time, so this
--   switch never weakens one of them and the dialog is never a bypass. The
--   owning project is read from the chat session, so a forged payload can
--   never queue generation in another project's lane. Submitting only queues
--   asynchronous generation: produced cases still wait for the human review
--   loop, the dialog never auto-approves a case, and the card states that
--   gate up front.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf, reservation,
--   popup-rule, mock-space or mock-stub drafts.
--
-- Behaviour when off: an aiGen.batch.submit tool call still refuses to
--   execute, the answer itself is unaffected, and the turn journals one state
--   frame that names this setting path and key so the user knows where to
--   reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.aigen-batch-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 用例生成批次草案开关（默认开启；对话中 AI 提出提交一批需求做用例生成时附上草案卡，确认后走 AI 用例生成页同一批量提交链——需求条目非空、单条长度与单次条数上限照常在提交时复核，不通过会被拒绝并提示原因；提交仅是排队异步生成，产出的用例仍须人工在用例评审页逐条通过，不会自动生效；跨项目提交会被拒绝；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
