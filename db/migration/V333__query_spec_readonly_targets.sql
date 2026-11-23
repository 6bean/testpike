-- Declarative read-only query targets for the dialog aggregate cards (AO batch
-- P3, closing the QuerySpec debt first named under ).
--
-- Why this migration exists:
--   The live in-dialog cards must show numbers the server computed, never
--   numbers the model retyped (ruling: "data = server-side computation, the
--   model only composes intent"). The mechanism is a registered-target allow
--   list: the model may only aim a QuerySpec at a target declared here, and
--   the interpreter may only touch the columns declared here. That single
--   registry row set is boundary #1 of the four hard edges — assembly refuses
--   any target outside the set, so a hallucinated table or column can never
--   reach the database.
--   Column shape is deliberately dumb: dimension/measure whitelists are
--   comma-separated identifiers validated by the interpreter (never spliced
-- raw), the scope column forces every query into one project segment, and
--   max_rows caps the result so a card can never ask for an unbounded scan.
--   Idempotent: guarded via IF NOT EXISTS so a re-run is a no-op.
--   Note: Flyway is not enabled - apply by hand (DBA channel), same as the
--   rest of db/migration.
-- Usage: docker exec -i testpike-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -' < this file

CREATE TABLE IF NOT EXISTS tp_query_target (
    id                BIGSERIAL    PRIMARY KEY,
    target_key        VARCHAR(64)  NOT NULL,
    base_table        VARCHAR(64)  NOT NULL,
    scope_column      VARCHAR(64)  NOT NULL,
    dimension_columns VARCHAR(512) NOT NULL DEFAULT '',
    measure_columns   VARCHAR(512) NOT NULL DEFAULT '',
    hard_filter       VARCHAR(256) NOT NULL DEFAULT '',
    max_rows          INT          NOT NULL DEFAULT 200,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_tp_query_target_key UNIQUE (target_key),
    CONSTRAINT ck_tp_query_target_max_rows CHECK (max_rows BETWEEN 1 AND 1000)
);

COMMENT ON TABLE tp_query_target IS
    'Allow list of read-only aggregate query targets the dialog QuerySpec may aim at; one row per registered view.';
COMMENT ON COLUMN tp_query_target.target_key IS
    'Stable name the model uses in QuerySpec.target (e.g. test_case).';
COMMENT ON COLUMN tp_query_target.scope_column IS
    'Project-slice column forced into every generated statement (implicit cross-project access is refused by construction).';
COMMENT ON COLUMN tp_query_target.dimension_columns IS
    'Comma-separated allow list of filterable/groupable columns; anything else is refused at compile time.';
COMMENT ON COLUMN tp_query_target.measure_columns IS
    'Comma-separated allow list of numeric columns sum/avg may read; empty = count-only target.';
COMMENT ON COLUMN tp_query_target.hard_filter IS
    'Server-side predicate always ANDed (e.g. soft-delete exclusion). Registry content only — never model-supplied.';

-- Seed the counting families the live cards need first: case, batch, defect.
INSERT INTO tp_query_target (target_key, base_table, scope_column, dimension_columns, measure_columns, hard_filter)
VALUES
    ('test_case',  'tp_test_case',  'project_id',
     'status,priority,phase,flaky,end_type,last_exec_result', 'review_fail_cnt,priority,confidence', 'deleted = 0'),
    ('exec_batch', 'tp_exec_batch', 'project_id',
     'status,type,schedule_type', '', ''),
    ('defect',     'tp_defect',     'project_id',
     'severity,status,source', 'occurrence_count', '')
ON CONFLICT (target_key) DO NOTHING;
