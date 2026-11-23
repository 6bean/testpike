-- =====================================================================
-- =====================================================================

CREATE TABLE IF NOT EXISTS tp_test_report (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    plan_id      BIGINT,
    title        VARCHAR(200) NOT NULL,
    range_type   SMALLINT     NOT NULL DEFAULT 1,
    range_label  VARCHAR(300),
    report_json  TEXT         NOT NULL,
    status       SMALLINT     NOT NULL DEFAULT 1,
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_test_report_range CHECK (range_type BETWEEN 1 AND 2),
    CONSTRAINT ck_test_report_status CHECK (status = 1)
);
CREATE INDEX IF NOT EXISTS idx_test_report_project ON tp_test_report (project_id, id);
CREATE INDEX IF NOT EXISTS idx_test_report_plan ON tp_test_report (plan_id) WHERE plan_id IS NOT NULL;
COMMENT ON TABLE  tp_test_report IS '测试总结报告（L4 综合报告记录）';
COMMENT ON COLUMN tp_test_report.plan_id IS '来源计划（NULL=自定义时间范围）';
COMMENT ON COLUMN tp_test_report.range_type IS '1计划 2时间范围';
COMMENT ON COLUMN tp_test_report.report_json IS '聚合结果 JSON（范围/结果/缺陷分布/环境版本/结论）';
