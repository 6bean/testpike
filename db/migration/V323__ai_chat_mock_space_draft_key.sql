-- Factory switch for mock-space create draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose opening a mock space (the mount point of the
--   interface-simulation routing and stubbing plane) with the
--   mock.space.create write tool. Nothing runs until the human confirms the
--   card through the shared draft door, and the confirmed create runs the
--   SAME MockSpaceService.create chain as the space page's dialog — the
--   structural validator (name length, routing-key shape, mode/auth/miss-
--   policy one-of sets, rate/quota/chaos bounds), the project existence
--   check, the production-environment ban (env_type=4 refused) and the
--   site-wide routing-key uniqueness conflict all re-decide at landing
--   time, so this switch never weakens one of them and the dialog is never
--   a bypass. The produced space stays a DRAFT row: it opens the traffic
--   door under /mocks/ only after a human publishes it on the space page.
--   The owning project is read from the chat session (the create payload
--   carries no project field), so a forged call cannot open a space in
--   another project's lane.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf, reservation or
--   popup-rule drafts.
--
-- Behaviour when off: a mock.space.create tool call still refuses to
--   execute, the answer itself is unaffected, and the turn journals one
--   state frame that names this setting path and key so the user knows where
--   to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.mock-space-draft.enabled', 'true', '系统', 'bool', 0,
     'AI Mock 空间创建草案开关（默认开启；对话中 AI 提出新建 Mock 空间时附上草案卡，确认后走接口仿真 → Mock 空间页同一创建链——名称与路由标识规则、生产环境禁用与路由标识全站唯一冲突照常在创建时复核，不通过会被拒绝并提示原因；产出为草稿态空间，须在空间页人工发布后才对外提供访问，不会自动上线；归属项目取当前会话项目；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
