
ALTER TABLE tp_exec_batch ADD COLUMN IF NOT EXISTS pool_tag_id BIGINT;
COMMENT ON COLUMN tp_exec_batch.pool_tag_id IS '资源池标签 id（H4，tp_tag_dict.type=7；NULL=未选池零池闸）';
CREATE INDEX IF NOT EXISTS idx_exec_batch_pool_tag ON tp_exec_batch (pool_tag_id) WHERE pool_tag_id IS NOT NULL;

ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS pool_quota_pct SMALLINT;
COMMENT ON COLUMN tp_project.pool_quota_pct IS 'H4 项目在单池并发占比上限 %（1~100；NULL=不限）';
ALTER TABLE tp_project DROP CONSTRAINT IF EXISTS ck_project_pool_quota_pct;
ALTER TABLE tp_project ADD CONSTRAINT ck_project_pool_quota_pct CHECK (pool_quota_pct IS NULL OR pool_quota_pct BETWEEN 1 AND 100);
