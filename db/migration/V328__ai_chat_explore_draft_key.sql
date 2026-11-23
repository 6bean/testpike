-- Factory switch for exploration draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose starting an autonomous exploration session with
--   the explore.session.create write tool. Nothing runs until the human
--   confirms the card through the shared draft door, and the confirmed
--   write runs the SAME ExploreService.create chain the explore page's
--   dialog uses - the budget fuse, the goal non-blank and length mandates,
--   the web-form SSRF floor (internal and metadata addresses refused) and
--   the mobile-form env whitelist gate all re-decide at landing time, so
--   this switch never weakens one of them and the dialog is never a bypass.
--   Unlike the definition-saving draft domains, confirming THIS card starts
--   work: the explore kernel picks the session up and spends project AI
--   budget, which the card states up front and the run stays cancellable on
--   the explore page. The owning project is taken from the chat session (a
--   project field in the payload is ignored by design), so a forged payload
--   can never file an exploration into another project.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf, reservation,
--   popup-rule, mock-space, mock-stub, ai-generation, defect or
--   data-factory drafts.
--
-- Behaviour when off: an exploration write tool call still refuses to
--   execute, the answer itself is unaffected, and the turn journals one
--   state frame that names this setting path and key so the user knows where
--   to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.explore-draft.enabled', 'true', '系统', 'bool', 0,
     '自主探索草案开关（默认开启；对话中 AI 提出发起探索会话时附草案卡，确认后走探索页同一创建链——预算熔断、目标字数上限、起始地址合规底线（内网与元数据地址会被拒绝）与移动形态环境白名单照常在确认时复核，不通过会被拒绝并提示原因；归属项目取当前会话项目；确认即开始探索并占用项目 AI 预算，可在探索页随时取消；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
