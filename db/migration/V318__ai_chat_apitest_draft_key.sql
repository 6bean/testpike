-- Factory switch for api-test collection-defaults draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose saving an api-test collection's default request
--   headers and default auth block through the apitest.defaults.save write
--   tool. Like every AI write feature, nothing lands until the human confirms
--   the card through the shared draft door, and the confirmed save runs the
--   SAME ApiCollectionService.rename full save face as the collection page's
--   dialog — shape gates, vocabulary gate and audit stay single-sourced, and
--   the owning project is re-checked against the chat session so a forged
--   collection id cannot touch another project's data (factory default ON:
--   AI features ship enabled; a draft card is not a production change;
--   editing single requests stays a deliberate human action on /api-test).
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch or report drafts.
--
-- Behaviour when off: an apitest.defaults.save tool call still refuses to
--   execute, the answer itself is unaffected, and the turn journals one state
--   frame that names this setting path and key so the user knows where to
--   reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.apitest-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 接口集默认配置草案开关（默认开启；对话中 AI 提出保存接口集的默认请求头或默认认证配置时附上草案卡，确认后走接口集页同一保存链落库，名称与标签保持不变；接口集编号缺失、不属于当前项目或配置内容不合规时会被拒绝并提示原因；单个请求的编辑仍在接口测试页人工操作；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
