-- Server-side anchor injection for dialog edit drafts , §9.2 item 1).
--
-- Why this key exists:
--   Edit proposals used to carry the baseVersion the MODEL emitted inside the
--   tool-call body. In multi-turn dialogues the model routinely reuses the
--   version number from the previous turn, so the second round lands against
--   a stale anchor and the confirmation door must refuse (the "refresh and
--   ask the AI again" friction). The anchor is a system fact - the emitter
--   should never guess it.
--   With this key ON (factory default), the frame-ledger landing re-reads
--   the CURRENT entity version for a target-anchored edit draft and pins the
--   fresh anchor onto the ledger row when it differs from the body claim;
--   the card still shows the model-intended edit, the door re-checks against
--   the truth at confirm time. OFF = historical behavior (body claim wins).
--   Scope: edit drafts that name a target id (CASE_WRITE / REQUIREMENT_WRITE
--   edit shapes); create proposals carry no anchor and are untouched.
--   Field-level rebase on a genuinely stale card (§9.2 item 2) is the NEXT
--   batch; this switch only removes the model-guessing-the-anchor failure
--   mode, it does not weaken the optimistic-lock door itself.
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.draft-anchor-server-side.enabled', 'true', '系统', 'bool', 0,
     '对话编辑草案版本锚服务端注入（默认开启：确认卡落账时按目标对象现读当前版本覆盖模型报出的旧锚，多轮对话连续编辑不再因锚陈旧被误拒；关闭后沿用模型报出的锚；落库时版本复校与人工确认门照常执行）')
ON CONFLICT (config_key) DO NOTHING;
