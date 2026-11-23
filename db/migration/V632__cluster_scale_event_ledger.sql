-- V632: create the executor-pool autoscale event ledger.
-- The apply channel (KEDA or the fallback scaler, wired with the live fleet window) writes
-- one row per scale action; the demand reader consumes MAX(created_at) per pool as the
-- single source for the cooldown guardrail, so a scale issued seconds ago is not issued
-- again. This migration ships the table and the read-only consumption contract only —
-- no writer exists before the apply channel lands.
-- Manual apply (Flyway not enabled) via tools/ops/apply_slot_migration.sh;
-- regenerate db/schema-init.sql afterwards.
CREATE TABLE IF NOT EXISTS tp_cluster_scale_event (
    id               BIGSERIAL PRIMARY KEY,
    pool_key         VARCHAR(64)  NOT NULL,
    action           VARCHAR(16)  NOT NULL,
    desired_replicas INT          NOT NULL,
    current_replicas INT          NOT NULL,
    reason           VARCHAR(512),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cluster_scale_event_pool_time
    ON tp_cluster_scale_event (pool_key, created_at DESC);
COMMENT ON TABLE tp_cluster_scale_event IS
    'Append-only ledger of executor pool scale actions; the autoscaler cooldown guardrail reads the newest event time per pool';
COMMENT ON COLUMN tp_cluster_scale_event.pool_key IS
    'End-type token identifying the pool, matching the demand spectrum pool key';
COMMENT ON COLUMN tp_cluster_scale_event.action IS
    'Scale action kind written by the apply channel: scale_up, scale_down or drain';
COMMENT ON COLUMN tp_cluster_scale_event.reason IS
    'Human-readable cause line shown on the fleet page, naming the pool and the deciding input';
