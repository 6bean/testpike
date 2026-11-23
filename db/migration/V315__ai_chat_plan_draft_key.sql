-- Factory switch for test-plan draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose creating an execution plan (scope rules pull the
--   cases automatically) through the plan.create write tool. Like every AI
--   write feature, nothing lands until the human confirms the card through
--   the shared draft door, and the confirmed plan runs the SAME creation
--   chain as the plan wizard — so the factory default is ON (AI features
--   ship enabled; a draft card is not a production change; the plan still
--   lands in draft state and publishing stays a deliberate human click).
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill or review
--   drafts.
--
-- Behaviour when off: a plan.create tool call still refuses to execute,
--   the answer itself is unaffected, and the turn journals one state frame
--   that names this setting path and key so the user knows where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.plan-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 测试计划草案开关（默认开启；对话中 AI 提出新建执行计划时附上草案卡，确认后走计划向导同一创建链并自动拉入范围用例，计划落为草稿态、发布仍在计划页人工操作；环境不属于当前项目或范围未限定维度时会被拒绝并提示原因；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
