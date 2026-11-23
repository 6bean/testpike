-- Migration: collection-level run sets for the API test sandbox .
-- Why: running a whole collection needs a parent ledger above the per-request
-- tp_apitest_run rows. The set row carries the pinned definition snapshot
-- (definitions frozen at launch — design W-c2: the variable pool keeps evolving
-- during the run, definitions must not), the interruption mode (three fixed
-- spectra, never free expressions) and the aggregate traffic-light counts.
-- Child runs gain nullable run_set_id/seq_no: single-request runs keep the old
-- shape (both NULL) with zero drift.
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_apitest_run_set (
    id                 bigserial PRIMARY KEY,
    collection_id      bigint       NOT NULL REFERENCES tp_apitest_collection (id),
    project_id         bigint       NOT NULL,
    env_id             bigint,
    name               varchar(200) NOT NULL DEFAULT '',
    interruption_mode  varchar(32)  NOT NULL DEFAULT 'STOP_ON_ASSERT_FAIL',
    status             smallint     NOT NULL DEFAULT 0,
    pinned_json        jsonb        NOT NULL DEFAULT '[]'::jsonb,
    initial_vars_json  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    total_steps        int          NOT NULL DEFAULT 0,
    passed_count       int          NOT NULL DEFAULT 0,
    failed_count       int          NOT NULL DEFAULT 0,
    skipped_count      int          NOT NULL DEFAULT 0,
    created_by         bigint,
    created_at         timestamp    NOT NULL DEFAULT now(),
    updated_at         timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT ck_apitest_run_set_status
        CHECK (status IN (0, 1, 2, 3, 4)),
    CONSTRAINT ck_apitest_run_set_interruption
        CHECK (interruption_mode IN ('STOP_ON_ASSERT_FAIL', 'CONTINUE_AND_SUMMARY', 'CHAIN_DEP_ONLY'))
);

COMMENT ON TABLE tp_apitest_run_set IS
    'Whole-collection run ledger: definition snapshot pinned at launch, variable pool evolves; child rows live in tp_apitest_run via run_set_id/seq_no';
COMMENT ON COLUMN tp_apitest_run_set.status IS
    '0=queued 1=running 2=done-passed 3=done-with-failures 4=interrupted by the chosen mode';
COMMENT ON COLUMN tp_apitest_run_set.interruption_mode IS
    'STOP_ON_ASSERT_FAIL / CONTINUE_AND_SUMMARY / CHAIN_DEP_ONLY — form enums, no free expressions';
COMMENT ON COLUMN tp_apitest_run_set.pinned_json IS
    'Array of request definitions frozen at launch, tree order (ORDER BY id); source for the order-preview card and the CHAIN_DEP reference graph';
COMMENT ON COLUMN tp_apitest_run_set.initial_vars_json IS
    'Collection variable pool snapshot at launch (bookkeeping/display only — the live pool the executor seeds from is re-read per hop)';

CREATE INDEX IF NOT EXISTS idx_apitest_run_set_collection
    ON tp_apitest_run_set (collection_id);

ALTER TABLE tp_apitest_run
    ADD COLUMN IF NOT EXISTS run_set_id bigint REFERENCES tp_apitest_run_set (id);
ALTER TABLE tp_apitest_run
    ADD COLUMN IF NOT EXISTS seq_no int;

COMMENT ON COLUMN tp_apitest_run.run_set_id IS
    'Owning collection run (NULL = standalone single-request run, the pre-T369 shape)';
COMMENT ON COLUMN tp_apitest_run.seq_no IS
    '1-based position inside the pinned sequence (NULL for standalone runs)';

CREATE INDEX IF NOT EXISTS idx_apitest_run_run_set
    ON tp_apitest_run (run_set_id, seq_no);
