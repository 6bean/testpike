-- V552__test_case_evidence_grade.sql — evidence grade for off-agent script write-back.
-- The MCP tool script.writeback records how much proof a case script carries:
--   0 = no evidence on file (default; every pre-existing row lands here),
--   1 = off-platform self-report without an evidence package,
--   2 = off-platform evidence package recorded,
--   3 = platform trial-run re-validation passed.
-- Existing rows keep 0 through the column default only; no backfill UPDATE is
-- issued, so historical rows are never rewritten retroactively.
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS evidence_grade SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE tp_test_case DROP CONSTRAINT IF EXISTS ck_test_case_evidence_grade;
ALTER TABLE tp_test_case ADD CONSTRAINT ck_test_case_evidence_grade
    CHECK (evidence_grade IN (0, 1, 2, 3));

COMMENT ON COLUMN tp_test_case.evidence_grade IS
    'Evidence grade of the current script revision: 0=none, 1=off-platform self-report, 2=evidence package on file, 3=platform re-validation passed';
