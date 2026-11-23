-- Migration: switches and the first verified entry for the pass-through change landing.
--
-- Why these rows exist:
--   The read door ships enabled (it changes nothing). Landing a change is a production-write
--   surface, so it ships CLOSED and stays closed until an operator opens it on the system
--   configuration page — the deliberate exception to "AI features ship enabled", recorded here
--   rather than left implicit. Two switches therefore govern one feature and must be named
--   separately in refusals:
--     * ai.api-access.write.enabled          — does a confirmed card actually execute
--     * ai.chat.api-access-write-draft.enabled — does the dialog emit the card at all
--   The dialog-side switch stays on: the safe default is to show the human the proposal, while the
--   one that touches data stays off until it is opened deliberately.
--
-- The verified entry is the pilot of the change landing: one internal door whose chain was read
-- before registering it (the tag create path validates name/length/uniqueness itself, and the
-- pass-through landing re-stamps the owning project from the chat session and re-checks the
-- confirmer's membership, so the payload cannot choose the project). Registration is per entry —
-- widening this set is a human decision made here, not something the generated catalog does by
-- itself.
--
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.api-access.write.enabled', '0', '系统', 'string', 0,
     '接口直通变更落地开关（默认关闭：确认后只记不执行；打开后，已核验登记的变更条目在用户确认草案卡后才会执行；逐条目登记见 tp_ai_api_access_governance）'),
    ('ai.chat.api-access-write-draft.enabled', '1', '系统', 'string', 0,
     '对话是否给出接口直通的变更草案卡（默认开启；在本页修改即时生效；关闭后此类变更不再出卡，读取不受影响）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('POST /internal/tags', 1, 1,
     '新建项目标签。名称/长度/同项目重名由标签服务自身校验；归属项目取自对话会话并由落地门改写，确认人的项目成员身份在落地前复核，载荷无法指定其它项目。',
     NULL)
ON CONFLICT (endpoint_key) DO NOTHING;
