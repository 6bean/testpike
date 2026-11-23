-- ============================================================
-- ============================================================

CREATE TABLE IF NOT EXISTS tp_export_task (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT       NOT NULL REFERENCES tp_project(id),
    report_type  VARCHAR(30)  NOT NULL,   -- personal_review/team_board/ai_quality/coverage/execution_stat
    masked       SMALLINT     NOT NULL DEFAULT 0,
    status       SMALLINT     NOT NULL DEFAULT 1,
    file_key     VARCHAR(200),
    row_count    INT,
    error_detail VARCHAR(500),
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    finished_at  TIMESTAMP
);

COMMENT ON TABLE  tp_export_task IS '报表导出任务（J3：>5000 条异步导出 + 站内信通知下载）';
COMMENT ON COLUMN tp_export_task.report_type IS '报表类型：personal_review/team_board/ai_quality/coverage/execution_stat';
COMMENT ON COLUMN tp_export_task.masked IS '脱敏导出（E5 规则：账号/手机号打码）';
COMMENT ON COLUMN tp_export_task.status IS '1=处理中 2=完成 3=失败';

CREATE INDEX IF NOT EXISTS idx_export_task_project ON tp_export_task (project_id, id DESC);
