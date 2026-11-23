-- Migration: platform release closed loop (AF batch S8A) — command queue gains
-- four platform/agent command types, schema-version ledger shell, health-gate K8 keys.
-- Semantics (03 §2.9 contract leads; sync CHECK + ClusterCommandServiceImpl + 03 = same segment):
--   1) tp_cluster_command CHECK extends with platform-service-upgrade /
--      platform-service-rollback / agent-upgrade / agent-rollback (consumer =
--      installkit service-agent over the enroll-token channel, fail-closed gate
--      on missing service code / pinned version / artifact url / sha256 lives in
--      core enqueue, not in the agent);
--   2) tp_schema_version ledger shell (decision point C: Flyway not enabled,
--      migrations run manually — table records applied versions once the
--      migration runner lands; db-schema family stays grey "not reported" this
--      phase, zero rows, never fabricated);
--   3) release.health-gate.* three K8 keys (decision point D): probe-count 5 ×
--      interval 2s + startup budget 90s defaults, hot-updatable, agent-local
-- health gate consumes them at — hardcoded values are banned.
-- Idempotent: DROP CONSTRAINT IF EXISTS + CREATE TABLE IF NOT EXISTS + ON CONFLICT DO NOTHING.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V224__platform_command_types_and_health_gate.sql
-- After landing new keys, rebuild the config snapshot (pit 21):
--   python3 scripts/tmp_m8p4_build_snapshot.py

-- 1) command-type CHECK 6 -> 10 (same set as 03 §2.9 + ClusterCommandServiceImpl.COMMAND_TYPES)
ALTER TABLE tp_cluster_command DROP CONSTRAINT IF EXISTS ck_cluster_command_type;
ALTER TABLE tp_cluster_command
    ADD CONSTRAINT ck_cluster_command_type CHECK
    (command_type IN ('engine-install', 'engine-uninstall', 'engine-upgrade',
                      'engine-rollback', 'scale-in', 'node-remove',
                      'platform-service-upgrade', 'platform-service-rollback',
                      'agent-upgrade', 'agent-rollback'));
COMMENT ON COLUMN tp_cluster_command.command_type IS
    'Command-type domain (CHECK ten members: V172 four + V178 two + AF platform/agent four; extending the set requires syncing 03 §2.9)';

-- 2) schema-version ledger shell (zero rows this phase; written by the future
-- migration runner, db-schema family reports grey "not uploaded" honestly)
CREATE TABLE IF NOT EXISTS tp_schema_version (
    id           BIGSERIAL   PRIMARY KEY,
    version      VARCHAR(20) NOT NULL UNIQUE,
    description  VARCHAR(200),
    script_name  VARCHAR(200) NOT NULL,
    applied_by   VARCHAR(50),
    applied_at   TIMESTAMP   NOT NULL DEFAULT now(),
    success      BOOLEAN     NOT NULL DEFAULT true
);
COMMENT ON TABLE tp_schema_version IS
    'Ledger of applied migration versions (AF decision-C shell: zero rows this phase, db-schema family reports grey "not uploaded"; the future migration runner writes rows here — never fabricated manually)';
COMMENT ON COLUMN tp_schema_version.version IS 'Migration version (matches the V number in db/migration, UNIQUE guards double-booking)';
COMMENT ON COLUMN tp_schema_version.success IS 'Execution outcome (false = failed run kept on record, never silent)';

-- 3) agent-local health gate K8 keys (decision point D defaults; hot-updatable)
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('release.health-gate.probe-count', '5', '机群与升级', 'int', 0,
     '平台服务升级健康闸：连续探测通过次数（主机 agent 本地换包重启后 health 连续 N 次 UP 才算成功，任一次失败=立即回切旧链接自救，不等平台判活；AF T354 决策点D缺省）'),
    ('release.health-gate.probe-interval-seconds', '2', '机群与升级', 'int', 0,
     '平台服务升级健康闸：两次探测的间隔秒（agent 本地消费，热更即生效）'),
    ('release.health-gate.startup-budget-seconds', '90', '机群与升级', 'int', 0,
     '平台服务升级健康闸：单服务启动预算超时秒（预算内未达连续通过=判失败自动回滚，禁无限等待）')
ON CONFLICT (config_key) DO NOTHING;
