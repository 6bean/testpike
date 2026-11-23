-- =====================================================================
-- =====================================================================

CREATE TABLE IF NOT EXISTS tp_regression_suite (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id              BIGINT       NOT NULL,
    name                    VARCHAR(200) NOT NULL,
    case_ids_json           TEXT,
    env_ids_json            TEXT,
    default_assignee_ids_json TEXT,
    cron_expr               VARCHAR(100),
    last_run_at             TIMESTAMP,
    status                  SMALLINT     NOT NULL DEFAULT 1,
    created_by              BIGINT,
    created_at              TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_regression_suite_status CHECK (status BETWEEN 1 AND 2)
);
CREATE INDEX IF NOT EXISTS idx_regression_suite_project ON tp_regression_suite (project_id, id);
COMMENT ON TABLE  tp_regression_suite IS '回归集（L3 可复用用例集+环境+定期执行）';
COMMENT ON COLUMN tp_regression_suite.status IS '1启用 2停用';
COMMENT ON COLUMN tp_regression_suite.last_run_at IS '上次一键生成计划/批次时间';
