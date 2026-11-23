-- Migration: dispatch stamp + skipped terminal for collection-run orchestration .
-- Why: the orchestrator sends one hop at a time and must never double-dispatch
-- when two result callbacks race inside one set. A NULL dispatch stamp is the
-- durable "never sent" marker — the row-level guard paired with the set-row
-- FOR UPDATE lock (docs/10 concurrency protection, same posture).
-- Child status gains 4 = suppressed skip (interrupted chain hop: terminal
-- without an envelope, honest traffic-light accounting).
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

ALTER TABLE tp_apitest_run
    ADD COLUMN IF NOT EXISTS dispatched_at timestamp;

COMMENT ON COLUMN tp_apitest_run.dispatched_at IS
    'When the executor envelope for this run was created (NULL = never dispatched; the advance guard never re-sends a stamped row)';
COMMENT ON COLUMN tp_apitest_run.status IS
    '0=pending 1=passed 2=assertion failed 3=execution error 4=skipped by the collection interruption mode';

CREATE INDEX IF NOT EXISTS idx_apitest_run_set_pending
    ON tp_apitest_run (run_set_id, seq_no)
    WHERE run_set_id IS NOT NULL AND dispatched_at IS NULL;
