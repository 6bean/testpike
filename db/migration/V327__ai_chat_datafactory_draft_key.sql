-- Factory switch for data-factory draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose saving a new data-generation script or editing an
--   existing one with the datafactory.create / datafactory.update write
--   tools. Nothing runs until the human confirms the card through the shared
--   draft door, and the confirmed write runs the SAME DataFactoryService
--   create/update chain as the data-factory page's editor — the type one-of
--   (API/SQL/script), the name and content mandates, the env-belongs-to-
--   project check and the pool-key grammar plus per project+env uniqueness
--   gate all re-decide at landing time, so this switch never weakens one of
--   them and the dialog is never a bypass. Confirming a card only saves the
--   script definition; executing it (which dials the real engine and writes
--   the data pool) stays a deliberate human click. The owning project is
--   taken from the chat session (the create payload's project field is
--   ignored by design) and an edit re-checks the target row's project, so a
--   forged payload can never touch another project's scripts.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf, reservation,
--   popup-rule, mock-space, mock-stub, ai-generation or defect drafts.
--
-- Behaviour when off: a data-factory write tool call still refuses to
--   execute, the answer itself is unaffected, and the turn journals one
--   state frame that names this setting path and key so the user knows where
--   to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.datafactory-draft.enabled', 'true', '系统', 'bool', 0,
     '数据工厂草案开关（默认开启；对话中 AI 提出新建或修改造数脚本时附上草案卡，确认后走数据工厂页同一保存链——造数类型、环境须属于当前项目、数据键名格式与同环境占用冲突照常在确认时复核，不通过会被拒绝并提示原因；确认只保存脚本定义，运行造数仍在数据工厂页人工点击执行；跨项目建脚本与改脚本会被拒绝；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
