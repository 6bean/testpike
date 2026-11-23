-- =====================================================================
-- Migration: batch mobile performance snapshot columns
-- tp_batch_report gains mobile_perf_json + prev_batch_id so the
-- finalization-time report carries the batch-level rollup of the mobile
-- sampling numbers already landed on each task's result_ext
-- (mobilePerfSummary), together with the previous-cycle comparison:
-- the nearest earlier finalized batch of the same project and environment.
-- Honest absence: batches without any mobile reading keep mobile_perf_json
-- NULL (never zero-filled); the first batch of a cycle keeps
-- prev_batch_id NULL and its rollup rows carry no prevMedian/delta.
-- Flyway is not enabled in dev; apply manually with
-- tools/ops/apply_slot_migration.sh, then regenerate db/schema-init.sql
-- with tools/ops/gen_schema_init.sh.
-- =====================================================================
ALTER TABLE tp_batch_report ADD COLUMN IF NOT EXISTS mobile_perf_json JSONB;
ALTER TABLE tp_batch_report ADD COLUMN IF NOT EXISTS prev_batch_id BIGINT;

COMMENT ON COLUMN tp_batch_report.mobile_perf_json IS 'Batch mobile sampling rollup: {"metrics":{"<name>":{"median","taskCount"[,"prevMedian","delta"]},"prevBatchId"}; computed once at finalization from reported task numbers, NULL when no task reported mobile metrics';
COMMENT ON COLUMN tp_batch_report.prev_batch_id IS 'Previous-cycle batch (nearest earlier finalized batch of the same project and environment, excluding self; NULL for the first batch); provenance anchor for prevMedian/delta inside the mobile rollup';
