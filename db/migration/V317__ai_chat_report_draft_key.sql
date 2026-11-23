-- Factory switch for test-report draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose generating a test summary report (over a plan or a
--   time window) through the report.generate write tool. Like every AI write
--   feature, nothing lands until the human confirms the card through the
--   shared draft door, and the confirmed report runs the SAME generation
--   chain as the report page's dialog — so the factory default is ON (AI
--   features ship enabled; a draft card is not a production change; the
--   report row is a snapshot record, exporting and deleting stay deliberate
--   human clicks on the test-report page).
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan or batch drafts.
--
-- Behaviour when off: a report.generate tool call still refuses to execute,
--   the answer itself is unaffected, and the turn journals one state frame
--   that names this setting path and key so the user knows where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.report-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 测试总结报告草案开关（默认开启；对话中 AI 提出生成测试总结报告时附上草案卡，确认后走报告页同一生成链落为报告记录，导出与删除仍在测试报告页人工操作；未选计划、时间范围缺起止日期或计划不属于当前项目时会被拒绝并提示原因；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
