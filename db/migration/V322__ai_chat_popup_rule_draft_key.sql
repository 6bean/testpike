-- Factory switch for popup-rule-learning draft cards in the online AI dialog.
--
-- Why this key exists:
--   The dialog can propose learning popup patterns from one finished
--   execution task with the appauto.popup-rule.learn write tool. Nothing
--   runs until the human confirms the card through the shared draft door,
--   and the confirmed learn runs the SAME AppautoPopupRuleService.learn
--   chain as the approval view's learn button — the learning switch
--   (ai.appauto.popup-rule-learn, off = refused in plain words with zero
--   model calls), the sensitive-frame exclusion, the structure gate and the
--   pending-approval status all re-decide at landing time, so this switch
--   never weakens one of them and the dialog is never a bypass. The rule
--   produced by a confirmed card stays a pending draft: it enters the rule
--   library only after a human approves it on the popup-rule approval view.
--   The task's owning project is read from tp_exec_task and compared
--   against the chat session before any frame is collected, so a forged
--   task id can never reach another project's watch frames.
--   Operators who want a quieter dialog for one domain can close exactly
--   this key without silencing case, tag, prompt-template, skill, review,
--   plan, batch, report, api-test, api-inventory, perf or reservation
--   drafts.
--
-- Behaviour when off: an appauto.popup-rule.learn tool call still refuses
--   to execute, the answer itself is unaffected, and the turn journals one
--   state frame that names this setting path and key so the user knows where
--   to reopen it.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.popup-rule-draft.enabled', 'true', '系统', 'bool', 0,
     'AI 弹窗规则学习草案开关（默认开启；对话中 AI 提出从已完成任务学习弹窗规则时附上草案卡，确认后走弹窗规则审批页同一学习链——学习开关、敏感帧排除与结构闸照常在学习时复核；产出为待批准规则草稿，须在「APP 自动化 → 弹窗规则」人工批准后才进入规则库，不会自动入库；任务归属取当前会话项目，跨项目任务会被拒绝并提示原因；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
