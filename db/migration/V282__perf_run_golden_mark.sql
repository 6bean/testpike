-- Migration: B-7 golden-run marking. tp_perf_run gains a nullable "mark" column
-- with three states (GOLDEN = the trustworthy reference run of its scenario,
-- CANDIDATE = once-golden or candidate for promotion, VOID = invalidated, kept for
-- history but excluded from replay and golden-comparison consumption downstream).
-- NULL = unmarked, which is exactly the semantics of every existing row: zero
-- behavioral shock for the legacy population (no backfill by design).
-- The partial unique index is the last-resort DB guard for "at most one golden per
-- scenario"; the service demotes the previous golden atomically before promoting, so
-- the index only fires when someone bypasses the service.
-- Usage: docker cp db/migration/V282__perf_run_golden_mark.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V282__perf_run_golden_mark.sql
ALTER TABLE tp_perf_run ADD COLUMN IF NOT EXISTS mark VARCHAR(20);

ALTER TABLE tp_perf_run DROP CONSTRAINT IF EXISTS ck_perf_run_mark;
ALTER TABLE tp_perf_run ADD CONSTRAINT ck_perf_run_mark
    CHECK (mark IS NULL OR mark IN ('GOLDEN', 'CANDIDATE', 'VOID'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_perf_run_golden_per_scenario
    ON tp_perf_run (scenario_id) WHERE mark = 'GOLDEN';

COMMENT ON COLUMN tp_perf_run.mark IS 'Run mark (B-7): GOLDEN / CANDIDATE / VOID; NULL = unmarked legacy semantics; at most one golden per scenario';
