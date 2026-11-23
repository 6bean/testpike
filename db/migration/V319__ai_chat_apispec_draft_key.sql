-- Factory switch for api-inventory contract-pull draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose pulling an OpenAPI contract through the URL entry
--   with the apiSpec.pull write tool. Like every AI write feature, nothing
--   lands until the human confirms the card through the shared draft door,
--   and the confirmed pull runs the SAME ApiSpecService.fetchFromUrl chain as
--   the inventory page's pull dialog — the outbound whitelist, the timeout
--   and size gates, the OpenAPI format parse, the same-content conflict and
--   the version chain stay single-sourced, and the owning project is taken
--   from the chat session so a model-forged url cannot append onto another
--   project's version chain (factory default ON: AI features ship enabled; a
--   draft card is not a production change; uploading a contract file stays a
--   deliberate human action on /api-inventory).
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report or api-test drafts.
--
-- Behaviour when off: an apiSpec.pull tool call still refuses to execute,
--   the answer itself is unaffected, and the turn journals one state frame
--   that names this setting path and key so the user knows where to reopen
--   it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.apispec-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 接口契约拉取草案开关（默认开启；对话中 AI 提出从 URL 拉取接口契约入库时附上草案卡，确认后走接口清单页同一拉取链挂版本链，同内容重复会提示已存在；契约归属取当前会话项目；契约名称或文档地址缺失、地址不合规或内容不合规时会被拒绝并提示原因；契约文件的上传导入仍在接口清单页人工操作；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
