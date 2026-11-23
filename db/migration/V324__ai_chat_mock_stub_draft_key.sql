-- Factory switch for mock-stub rule draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose filing or editing a mock stub rule (the response
--   source of the interface-simulation plane) with the mock.stub.create and
--   mock.stub.update write tools. Nothing runs until the human confirms the
--   card through the shared draft door, and the confirmed write runs the
--   SAME MockStubService.create/update chain as the endpoint page's editor —
--   the rule validator (name length, method one-of, path shape including the
--   regex-compile and template-brace pairing gates, matcher/response JSON
--   shape, fault-parameter domain gate), the space-existence check, the
--   scenario/state declaration kernel and the optimistic version conflict
--   all re-decide at landing time, so this switch never weakens one of them
--   and the dialog is never a bypass. A new rule serves traffic only while
--   its owning space is published and the rule is enabled: publishing a
--   space stays a deliberate human click on the space page. The owning
--   space's project is re-checked against the chat session, so a forged
--   space or stub id can never file or edit a rule in another project's
--   lane.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf, reservation,
--   popup-rule or mock-space drafts.
--
-- Behaviour when off: a mock.stub.create / mock.stub.update tool call still
--   refuses to execute, the answer itself is unaffected, and the turn
--   journals one state frame that names this setting path and key so the
--   user knows where to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.mock-stub-draft.enabled', 'true', '系统', 'bool', 0,
     'AI Mock 打桩规则草案开关（默认开启；对话中 AI 提出新建或编辑 Mock 打桩规则时附上草案卡，确认后走接口仿真 → Mock 接口页同一保存链——方法、路径模式、匹配器与响应结构、场景状态声明照常在校验时复核，不通过会被拒绝并提示原因；编辑必须携带读取时的版本号，期间被他人改过会被拒绝并提示刷新；新规则须所属空间处于发布态且规则启用才对外提供访问，空间发布始终是人工决定；跨项目的空间或规则会被拒绝；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
