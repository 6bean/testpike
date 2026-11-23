-- Factory switch for flaky-quarantine draft cards in the online AI dialog
-- .
--
-- Why this key exists:
--   When the dialog's failure analysis judges a failure to be environmental
--   flakiness, the AI can propose quarantining the observed case through the
--   case.flaky.isolate write tool. Nothing moves until the human confirms
--   the card through the shared draft door, and the confirmed write runs the
--   SAME CaseService.setFlaky chain the workbench's one-click quarantine
--   uses - the audit row, the idempotent reason refresh and the version bump
--   all come from that chain, so this switch never weakens one of them and
--   the dialog is never a bypass. The door is one-directional: confirming a
--   card can only MARK a case flaky, releasing an isolated case (and the
--   later probe/restore scheduling) stays the cases page's human face.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf, reservation,
--   popup-rule, mock-space, mock-stub, ai-generation, defect, data-factory
--   or exploration drafts.
--
-- Behaviour when off: a quarantine write tool call still refuses to
--   execute, the answer itself is unaffected, and the turn journals one
--   state frame that names this setting path and key so the user knows
--   where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.flaky-draft.enabled', 'true', '系统', 'bool', 0,
     '失败隔离草案开关（默认开启；对话中 AI 判定失败为环境抖动时附草案卡，写明用例编号、判定依据与所基于的版本；确认后走用例工作台一键隔离同一链——审计行与隔离原因幂等刷新由该链携带，用例不属于会话项目或期间已被修改（草案基于旧版）会被拒绝并提示原因；本通道只支持隔离不支持解除，解除隔离仍在用例库页人工操作；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
