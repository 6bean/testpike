-- =====================================================================
-- =====================================================================

ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS deployed_version VARCHAR(50);
COMMENT ON COLUMN tp_project_env.deployed_version IS '最近一次部署版本/构建号（L6；部署动作记录，与 version_snapshot 同步）';

ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS deployed_at TIMESTAMP;
COMMENT ON COLUMN tp_project_env.deployed_at IS '最近一次部署时间（L6）';

ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS deployed_by BIGINT;
COMMENT ON COLUMN tp_project_env.deployed_by IS '最近一次部署操作人（L6，tp_user.id）';
