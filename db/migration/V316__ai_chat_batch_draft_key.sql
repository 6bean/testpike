-- Factory switch for execution-batch draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose creating a draft execution batch (checked cases
--   from the project library) through the batch.create write tool. Like every
--   AI write feature, nothing lands until the human confirms the card through
--   the shared draft door, and the confirmed batch runs the SAME creation
--   chain as the batches-page form — so the factory default is ON (AI features
--   ship enabled; a draft card is not a production change; the batch still
--   lands in draft state and starting it stays a deliberate human click).
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review or
--   plan drafts.
--
-- Behaviour when off: a batch.create tool call still refuses to execute,
--   the answer itself is unaffected, and the turn journals one state frame
--   that names this setting path and key so the user knows where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.batch-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 执行批次草案开关（默认开启；对话中 AI 提出新建执行批次时附上草案卡，确认后走批次页同一创建链并落为草稿批，启动批次仍在批次页人工操作；未勾选用例、用例不属于当前项目或端与环境不匹配时会被拒绝并提示原因；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
