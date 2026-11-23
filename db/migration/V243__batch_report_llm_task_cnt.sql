-- AI-fallback count on the batch report snapshot.
-- tp_batch_report stays a point-in-time snapshot row: the llm-task count is captured
-- at finalize time and never recomputed on the read path (same-row discipline shared
-- with the completion notice). A task counts into llm_task_cnt when its
-- result_ext.mockNotes audit array carries at least one row with source='llm' — the
-- hard provenance stamp the traffic plane writes whenever the paid LLM fallback rung
-- answered a miss. The badge tells reviewers "this batch passed with AI-generated
-- answers" and never blends into the plain mock count, which still includes
-- llm-served tasks because an llm answer is also a mock.
ALTER TABLE tp_batch_report
    ADD COLUMN IF NOT EXISTS llm_task_cnt INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_batch_report.llm_task_cnt IS 'Tasks whose mockNotes audit rows carry source=llm (snapshot at finalize; 0 for rows predating this column)';
