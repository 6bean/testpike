-- Factory switch for defect-library draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose filing, editing or moving a defect through its
--   state machine with the defect.create / defect.update / defect.transition
--   write tools. Nothing runs until the human confirms the card through the
--   shared draft door, and the confirmed write runs the SAME
--   DefectLibraryService create/update/transition chain as the defect page's
--   dialogs — the title ceiling, the severity one-of, the same-project checks
--   on the linked case/plan, the fixer-membership refusal, the closed-state
--   edit ban, the six-state transition table, the reason mandate on closing
--   or reopening and the optimistic version conflict all re-decide at landing
--   time, so this switch never weakens one of them and the dialog is never a
--   bypass. The owning project is taken from the chat session (the create
--   payload's project field is ignored by design), and edit/transition
--   re-check the target row's project, so a forged payload can never file or
--   move a defect in another project's lane.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf, reservation,
--   popup-rule, mock-space, mock-stub or ai-generation drafts.
--
-- Behaviour when off: a defect write tool call still refuses to execute, the
--   answer itself is unaffected, and the turn journals one state frame that
--   names this setting path and key so the user knows where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.defect-draft.enabled', 'true', '系统', 'bool', 0,
     '缺陷库草案开关（默认开启；对话中 AI 提出登记、编辑或流转缺陷时附上草案卡，确认后走缺陷库页同一写入链——标题长度、严重级别、关联用例与计划同项目、修复人须为项目成员、状态机流转合法性、关闭原因必填与版本冲突照常在确认时复核，不通过会被拒绝并提示原因；跨项目建单与改单会被拒绝；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
