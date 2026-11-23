-- AG batch : AI mock-generation job switches + creation-time budget pin.
--
-- Why this file exists:
--   1) tp_mock_ai_job (shell created in V229) gains a budget_snapshot JSONB column.
--      The cost account is pinned as the full snapshot body at job-creation time
--      (explore V57 precedent: consumers must never re-read the global keys later,
--      otherwise a hot config change would silently re-price an in-flight job).
--      The legacy budget_snapshot_id BIGINT column stays untouched — it is a
--      reserved reference slot, not the source of truth for this batch.
--   2) Three per-application-point sub-switches (feature plan §7): the master
--      gate ai.mock.enabled (seeded by V228) is ANDed with one switch per point.
--      Every switch defaults to '0' (off) — AI drafting never runs until an
--      operator opens both gates. M10 points (llm-fallback / record-summarize)
--      deliberately have no keys yet: their job types are refused outright this
--      period, so a switch would promise a path that does not exist.
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

ALTER TABLE tp_mock_ai_job ADD COLUMN IF NOT EXISTS budget_snapshot JSONB;

COMMENT ON COLUMN tp_mock_ai_job.budget_snapshot IS
    'Cost account pinned at creation time as the full snapshot body (estimate inputs + quotas); consumers read this column, never the live global keys';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.mock.nl-stub', '1', 'AI 配置', 'bool', 0,
     'A-1 自然语言建 Mock 分开关：默认关；与总闸 ai.mock.enabled 做与运算，任一关则拒绝创建草稿任务（报错说明原因+审计留痕）'),
    ('ai.mock.edge-scenarios', '1', 'AI 配置', 'bool', 0,
     'A-2 契约→边缘场景包分开关：默认关；与总闸做与运算，关态拒绝创建故障场景集任务'),
    ('ai.mock.spec-sync', '1', 'AI 配置', 'bool', 0,
     'A-5 契约变更→Mock 修补建议分开关：默认关；确定性挂标不受本闸影响，仅 AI 建议任务受控')
ON CONFLICT (config_key) DO NOTHING;
