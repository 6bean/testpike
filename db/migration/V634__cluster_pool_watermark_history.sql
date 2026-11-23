-- V634: create the executor-pool watermark history table (T294d water-level curve source).
-- The sampler component writes one row per pool per evaluation cycle (pending dispatch
-- depth, replica line, active executors, p95 wait); the fleet page curve reads bounded
-- windows newest-first. Rows past the retention bound are pruned by the sampler's paired
-- cleanup step, so this append-only table never grows without a recycling path (R57).
-- The retention bound is an operator-tunable tp_sys_config key shipped here; the ledger's
-- compiled fallback equals the seed value on purpose (same posture as V631), so a database
-- without the row behaves exactly like a fresh install.
-- Idempotent: IF NOT EXISTS + ON CONFLICT DO NOTHING. Manual apply (Flyway not enabled)
-- via tools/ops/apply_slot_migration.sh; regenerate db/schema-init.sql afterwards.

CREATE TABLE IF NOT EXISTS tp_cluster_pool_watermark (
    id                    BIGSERIAL PRIMARY KEY,
    executor_type         VARCHAR(64)  NOT NULL,
    pending_dispatch_depth INT         NOT NULL,
    current_replicas      INT          NOT NULL,
    active_executors      INT          NOT NULL,
    p95_wait_seconds      DOUBLE PRECISION NOT NULL,
    sampled_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cluster_pool_watermark_type_time
    ON tp_cluster_pool_watermark (executor_type, sampled_at DESC);
COMMENT ON TABLE tp_cluster_pool_watermark IS
    'Periodic water-level snapshots per executor pool; the fleet page curve and capacity review read this history';
COMMENT ON COLUMN tp_cluster_pool_watermark.executor_type IS
    'End-type token identifying the pool, matching the autoscaler demand spectrum';
COMMENT ON COLUMN tp_cluster_pool_watermark.pending_dispatch_depth IS
    'Claimable backlog depth at sample time (tasks dispatched-to-be, not yet claimed)';
COMMENT ON COLUMN tp_cluster_pool_watermark.p95_wait_seconds IS
    'P95 seconds the claimable backlog had been waiting at sample time';
COMMENT ON COLUMN tp_cluster_pool_watermark.sampled_at IS
    'Server-side sample moment; rows older than the retention bound are pruned each cycle';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('cluster.autoscale-watermark-retention-days', '30', 'APP自动化', 'int', 0,
     '池水位历史保留天数（默认 30；超过该天数的采样行每轮自动清理，曲线只展示保留窗内的历史）')
ON CONFLICT (config_key) DO NOTHING;
