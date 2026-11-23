-- Factory switch for review-decision draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose a review decision (pass / need change / reject)
--   through the case.review write tool. Like every AI write feature, nothing
--   lands until the human confirms the card through the shared draft door,
--   and the confirmed decision runs the SAME state-machine chain as the
--   review workbench — so the factory default is ON (AI features ship
--   enabled; a draft card is not a production change). Operators who want a
--   quieter dialog for one domain can close exactly this key without
--   silencing case, tag, prompt-template, skill or repair drafts.
--
-- Behaviour when off: a case.review tool call still refuses to execute,
--   the answer itself is unaffected, and the turn journals one state frame
--   that names this setting path and key so the user knows where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.review-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 评审决议草案开关（默认开启；对话中 AI 提出用例评审结论时附上草案卡，确认后走评审工作台同一状态机链，版本冲突或状态不合法时会被拒绝并提示原因；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
