-- V238: mock-serving count on the batch report snapshot .
-- tp_batch_report is a point-in-time snapshot row; the mock-task count is captured
-- at finalize time (never recomputed on the read path) so the completion notice and
-- the report page read the same row. A task counts as mock-served when its
-- result_ext carries a non-empty "mockNotes" audit bucket written by the executor.
ALTER TABLE tp_batch_report
    ADD COLUMN IF NOT EXISTS mock_task_cnt INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_batch_report.mock_task_cnt IS 'Tasks in this batch whose result evidence carries a non-empty mockNotes bucket (snapshot at finalize; 0 for pre-V238 rows)';
