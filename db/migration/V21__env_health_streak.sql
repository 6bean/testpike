
ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS health_fail_streak SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN tp_project_env.health_fail_streak IS
    '连续健康检查失败次数（R1 抖动抑制：≥2 才 ENV_UNHEALTHY 告警；恢复或环境编辑（base_url 变更）清零）';
