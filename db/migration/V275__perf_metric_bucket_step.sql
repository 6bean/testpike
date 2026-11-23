-- Migration: A-7/B-4/B-6 — tp_perf_metric gains request-count buckets, a latency histogram,
--   an error-class breakdown and a step dimension, so full-run percentile and weighted error-rate
--   recomputation have raw material (per-second p95-max / equal-weight mean are mathematically
--   unsound as whole-run calibers).
-- Semantics:
--   req_count / fail_count = completed / failed requests of that second (and step); NULL = legacy
--     frame from an executor that predates bucket reporting — the judge then falls back to the
--     per-second caliber (single judgment family, degradation noted in the conclusion line).
--   duration_sum_ms = sum of response times that second; whole-run mean = SUM(duration_sum)/SUM(req).
--   hist = JSONB integer array of histogram bucket counts; bucket boundaries are a fixed contract
--     shared with core PerfHistogram (rank-based full-run percentiles merge these arrays).
--   error_class = JSONB object {timeout, refused, http4xx, http5xx, biz} counts for the TopN
--     error-class drawer; NULL = not collected.
--   step_key = '' (empty string, never NULL) for un-split frames, so existing rows stay valid
--     under the new uniqueness anchor (D3 ruling: column form, key migrated below).
-- Uniqueness: uq_perf_metric_frame moves from (run_id, second_offset) to
--   (run_id, second_offset, step_key) — replay idempotency anchor per step; legacy rows backfill
--   step_key='' so the index build cannot fail on duplicates.
-- K8 seeds (hot-updatable thresholds, never hardcoded at call sites):
--   perf.frames-flush-interval-seconds = how often the worker ships partial frames with heartbeats;
--   perf.max-duration-hours = launch-time guard rejecting runs longer than the soak batch limit.
-- Idempotent re-run: ADD COLUMN IF NOT EXISTS + guarded index drop/create + ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V275__perf_metric_bucket_step.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V275__perf_metric_bucket_step.sql
ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS req_count       BIGINT;
ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS fail_count      BIGINT;
ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS duration_sum_ms NUMERIC(14,2);
ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS hist            JSONB;
ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS error_class     JSONB;
ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS step_key        VARCHAR(64) NOT NULL DEFAULT '';

-- Value-domain guards (NULL-tolerant: legacy frames stay legal; bucket frames must satisfy
-- fail<=req and non-negative sums — the ingestion face rejects with a row-numbered message first).
ALTER TABLE tp_perf_metric DROP CONSTRAINT IF EXISTS ck_perf_metric_bucket_counts;
ALTER TABLE tp_perf_metric ADD CONSTRAINT ck_perf_metric_bucket_counts
    CHECK (fail_count IS NULL OR req_count IS NULL OR (fail_count >= 0 AND fail_count <= req_count));
ALTER TABLE tp_perf_metric DROP CONSTRAINT IF EXISTS ck_perf_metric_duration_sum;
ALTER TABLE tp_perf_metric ADD CONSTRAINT ck_perf_metric_duration_sum
    CHECK (duration_sum_ms IS NULL OR duration_sum_ms >= 0);

-- Uniqueness anchor migration: (run_id, second_offset) -> (run_id, second_offset, step_key).
UPDATE tp_perf_metric SET step_key = '' WHERE step_key IS NULL;
ALTER TABLE tp_perf_metric DROP CONSTRAINT IF EXISTS uq_perf_metric_frame;
CREATE UNIQUE INDEX IF NOT EXISTS uq_perf_metric_frame
    ON tp_perf_metric (run_id, second_offset, step_key);

COMMENT ON COLUMN tp_perf_metric.req_count IS 'Completed requests of this second and step; raw material for weighted error rate and full-run percentile recomputation. NULL = legacy frame without buckets, judge falls back to the per-second caliber';
COMMENT ON COLUMN tp_perf_metric.fail_count IS 'Failed requests of this second and step; always <= req_count, enforced by ck_perf_metric_bucket_counts';
COMMENT ON COLUMN tp_perf_metric.duration_sum_ms IS 'Sum of response times this second and step (ms); whole-run mean = SUM(duration_sum)/SUM(req)';
COMMENT ON COLUMN tp_perf_metric.hist IS 'Latency histogram bucket counts (JSONB integer array; bucket boundaries shared with core PerfHistogram; over-cap values land in the last bucket)';
COMMENT ON COLUMN tp_perf_metric.error_class IS 'Error-class counts (JSONB {timeout,refused,http4xx,http5xx,biz}); data source of the TopN error drawer, NULL = not collected';
COMMENT ON COLUMN tp_perf_metric.step_key IS 'Step key; empty string = un-split frame (legacy shape). Third column of the idempotency anchor so same-second frames of different steps stay distinct';

-- K8 key seeds (defaults mirror PerfConfigReader.DEFAULTS; hot-updatable).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.frames-flush-interval-seconds', '10', '系统', 'int', 0,
     '压测部分帧随心跳回传的最小间隔秒（执行器侧攒帧窗口；过小=派发通道噪音，过大=实况曲线迟滞）'),
    ('perf.max-duration-hours', '4', '系统', 'int', 0,
     '压测单次 run 最长时长小时闸（发起时拒单上限；长跑拆批的前提护栏，默认 4 小时= soak 分批口径）')
ON CONFLICT (config_key) DO NOTHING;
