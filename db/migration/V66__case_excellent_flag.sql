ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS excellent_flag SMALLINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_test_case_excellent
    ON tp_test_case(project_id, excellent_flag)
    WHERE deleted = 0 AND excellent_flag = 1;
