-- : widen tp_report scope_type domain with 3 = program (project set) dimension.
-- scope_id keeps NOT NULL semantics (V53 ruling 2): a program row stores the program id,
-- never NULL, so the UNIQUE(scope_type, scope_id, report_type, period_start) idempotent
-- overwrite keeps working for program snapshots exactly like project/global rows.
ALTER TABLE tp_report DROP CONSTRAINT ck_report_scope_type;
ALTER TABLE tp_report ADD CONSTRAINT ck_report_scope_type CHECK (scope_type IN (1, 2, 3));
