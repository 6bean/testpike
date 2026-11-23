-- Factory switch for perf-run launch draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose pressing a pressure run with the perf.run.launch
--   write tool. Launching is the heaviest write face the dialog can propose:
--   a run occupies one test environment for its whole duration. Like every
--   AI write feature, nothing runs until the human confirms the card through
--   the shared draft door, and the confirmed launch runs the SAME
--   PerfRunService.launch transaction as the workbench's launch dialog — the
--   production-change safety gate, the duration ceiling, the canary
--   pre-flight and the environment execution lock all re-decide at launch
--   time, so this switch never weakens one of them and the dialog is never a
--   bypass. The owning project is taken from the chat session, so a
--   model-forged scenario id cannot press another project's environment.
--   Designing or editing the scenario itself stays a deliberate human flow
--   on the perf workbench (the wizard is a multi-step design session).
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test or api-inventory drafts.
--
-- Behaviour when off: a perf.run.launch tool call still refuses to execute,
--   the answer itself is unaffected, and the turn journals one state frame
--   that names this setting path and key so the user knows where to reopen
--   it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.perf-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 压测发起草案开关（默认开启；对话中 AI 提出按场景发起压测时附上草案卡，确认后走压测工作台同一发起链——生产变更安全闸、时长上限、金丝雀预检与环境执行锁照常在发起时判定，不通过会被拒绝并提示原因；草案卡会注明本次运行占用一个测试环境；场景归属取当前会话项目；场景编号缺失或环境不匹配时会被拒绝并提示原因；场景的新建与编辑仍在压测工作台人工操作；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
