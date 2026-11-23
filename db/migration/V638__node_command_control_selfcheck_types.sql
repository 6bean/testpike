-- T294b pack B: extend the command-type domain with the two host-operation
-- types consumed by the installkit service-agent over the enroll-token
-- heartbeat channel (start/stop/restart one managed unit, and an honest
-- per-family state/version/health report). Mirrors
-- ClusterCommandServiceImpl.COMMAND_TYPES and docs/03 §2.9 — same set, wider
-- CHECK; no structural change.
-- Regenerate the install screen artifact with tools/ops/gen_schema_init.sh;
-- apply to a chain slot with tools/ops/apply_slot_migration.sh (Flyway is not
-- enabled here — slot replay is the single manual channel).

ALTER TABLE tp_cluster_command DROP CONSTRAINT IF EXISTS ck_cluster_command_type;
ALTER TABLE tp_cluster_command
    ADD CONSTRAINT ck_cluster_command_type CHECK
    (command_type IN ('engine-install', 'engine-uninstall', 'engine-upgrade',
                      'engine-rollback', 'scale-in', 'node-remove',
                      'platform-service-upgrade', 'platform-service-rollback',
                      'agent-upgrade', 'agent-rollback',
                      'platform-service-control', 'node-self-check'));
COMMENT ON COLUMN tp_cluster_command.command_type IS
    'Command-type domain (CHECK twelve members: V172 four + V178 two + AF T354 four + T294b two; extending the set requires syncing 03 §2.9)';
