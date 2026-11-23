-- Migration: platform wave-plan data model (AF batch, one-click platform upgrade).
-- What this segment lands and why:
--   1) tp_upgrade_plan.scope gains 'platform' — the one-click "upgrade the whole
--      platform to vX" plan is a third scope beside engine/node (same state
-- machine and CAS rules as /, no second scheduler allowed);
--   2) tp_upgrade_plan_step.kind gains 'platform' plus two new columns:
--      component_family (which platform family this step swaps) and wave_seq
--      (position in the dependency-derived wave order). The wave order is
--      persisted on the data plane so no consumer re-derives a second ordering
--      spectrum; rollback simply walks wave_seq DESC.
--      The old unique index (plan_id,node_id,COALESCE(engine_code,'')) collapsed
--      several platform families per host into one row (engine_code is empty for
--      platform steps) — rebuilt with the family discriminator added.
--   3) tp_cluster_component.last_known_good — the rollback target source per
--      family ("never guess a version" discipline: one-click rollback consumes
--      the ledger, not a user-typed version).
--   4) release.production.dual-review K8 key (default 0 = permissive; 1 =
--      production plans additionally require a maintenance window and a second
--      reviewer — hot-updatable, no hardcoded policy).
-- Idempotent: DROP CONSTRAINT IF EXISTS + re-add, ADD COLUMN IF NOT EXISTS,
-- index swap DROP/CREATE, seed ON CONFLICT DO NOTHING.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file
-- After landing the new key, rebuild the config snapshot (bookkeeping pit 21):
--   python3 scripts/tmp_m8p4_build_snapshot.py

-- 1) plan scope CHECK 2 -> 3 (same set as UpgradePlanServiceImpl.SCOPES)
ALTER TABLE tp_upgrade_plan DROP CONSTRAINT IF EXISTS ck_upgrade_plan_scope;
ALTER TABLE tp_upgrade_plan
    ADD CONSTRAINT ck_upgrade_plan_scope CHECK (scope IN ('engine', 'node', 'platform'));
COMMENT ON COLUMN tp_upgrade_plan.scope IS
    'Upgrade scope (CHECK three members: engine=engine runtime batch, node=host agent batch, platform=one-click whole-platform wave plan; adding a member requires syncing UpgradePlanServiceImpl.SCOPES)';

-- 2) step kind CHECK 2 -> 3 + wave-plan columns + rebuilt unique index
ALTER TABLE tp_upgrade_plan_step DROP CONSTRAINT IF EXISTS ck_upgrade_plan_step_kind;
ALTER TABLE tp_upgrade_plan_step
    ADD CONSTRAINT ck_upgrade_plan_step_kind CHECK (kind IN ('engine', 'node', 'platform'));
COMMENT ON COLUMN tp_upgrade_plan_step.kind IS
    'Step kind: engine=engine runtime step (engine_code set); node=host agent step (engine_code empty); platform=platform family wave step (component_family + wave_seq set)';

ALTER TABLE tp_upgrade_plan_step ADD COLUMN IF NOT EXISTS component_family VARCHAR(80);
ALTER TABLE tp_upgrade_plan_step ADD COLUMN IF NOT EXISTS wave_seq SMALLINT;
COMMENT ON COLUMN tp_upgrade_plan_step.component_family IS
    'Platform family swapped by this step (core/admin/api/ai-service/frontend/mock-server/executor-agent); NULL for engine/node steps';
COMMENT ON COLUMN tp_upgrade_plan_step.wave_seq IS
    'Wave position inside the plan (ascending = execution order, single source WavePlanGenerator.WAVE_ORDER; rollback walks DESC); NULL for engine/node steps';

DROP INDEX IF EXISTS uq_upgrade_plan_step_node;
CREATE UNIQUE INDEX IF NOT EXISTS uq_upgrade_plan_step_node
    ON tp_upgrade_plan_step (plan_id, node_id, COALESCE(engine_code, ''), COALESCE(component_family, ''));

-- 3) last-known-good ledger column (rollback target source, never guessed)
ALTER TABLE tp_cluster_component ADD COLUMN IF NOT EXISTS last_known_good VARCHAR(80);
COMMENT ON COLUMN tp_cluster_component.last_known_good IS
    'Last version this family on this node was confirmed healthy (written by the upgrade acceptance wave; one-click rollback targets this value instead of guessing)';

-- 4) production dual-review K8 key (decision E: default permissive, hot-updatable)
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('release.production.dual-review', '0', '机群与升级', 'bool', 0,
     '生产环境一键升级双人复核开关：0=关（非生产持权限即可发起，生产同样放行）；1=开严（生产范围计划启动前须申报维护窗口并经第二人复核确认，复用审批流模式；热更即生效，禁硬编码策略）')
ON CONFLICT (config_key) DO NOTHING;
