-- Factory switch for prompt-template draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose saving a new prompt-template version through the
--   promptTpl.save write tool. Like every AI write feature, nothing lands
--   until the human confirms the card through the shared draft door, so the
--   factory default is ON (AI features ship enabled; a draft card is not a
--   production change). Operators who want a quieter dialog for one domain
--   can close exactly this key without silencing case, tag or repair drafts.
--
-- Behaviour when off: a promptTpl.save tool call still refuses to execute,
--   the answer itself is unaffected, and the turn journals one state frame
--   that names this setting path and key so the user knows where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.prompttpl-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 提示词模板草案开关（默认开启；对话中 AI 提出保存提示词模板时附上草案卡，确认后作为族内新版本写入，默认灰度状态；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
