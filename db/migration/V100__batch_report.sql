
CREATE TABLE IF NOT EXISTS tp_batch_report (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id             BIGINT       NOT NULL UNIQUE,
    project_id           BIGINT       NOT NULL,
    total                INT          NOT NULL DEFAULT 0,
    pass_cnt             INT          NOT NULL DEFAULT 0,
    fail_cnt             INT          NOT NULL DEFAULT 0,
    skip_cnt             INT          NOT NULL DEFAULT 0,
    timeout_cnt          INT          NOT NULL DEFAULT 0,
    pass_rate            NUMERIC(6, 4),
    fail_dist_json       JSONB,
    attr_json            JSONB,
    duration_ms          BIGINT,
    env_version_snapshot VARCHAR(100),
    batch_status         SMALLINT     NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_batch_report_project ON tp_batch_report (project_id, batch_id DESC);

COMMENT ON TABLE tp_batch_report IS '批次级汇总报告快照（I7）：时点落行，通知与报告同源；uk(batch_id) 幂等';
COMMENT ON COLUMN tp_batch_report.pass_rate IS '通过率=通过÷终态(3/4/5)，PassRateSql.RATE_EXPR 单处定义（禁第二处计算）';
COMMENT ON COLUMN tp_batch_report.fail_dist_json IS '失败/超时任务 error_type 四分类分布（1产品缺陷2定位错误3环境4依赖，NULL 归 "0" 桶）';
COMMENT ON COLUMN tp_batch_report.attr_json IS '失败/超时任务 AI 归因三分类占比（迁移件列；NULL=未归类归 "unclassified" 桶）';
