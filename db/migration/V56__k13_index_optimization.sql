
CREATE INDEX IF NOT EXISTS idx_test_case_project_created
    ON tp_test_case (project_id, created_at DESC);
COMMENT ON INDEX idx_test_case_project_created
    IS 'K13 T208 列表分页 ORDER BY created_at DESC 消除排序（慢 SQL 清单 #12 实证 Seq+heapsort→Index+Incremental Sort）';
