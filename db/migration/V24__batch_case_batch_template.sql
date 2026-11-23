
CREATE TABLE IF NOT EXISTS tp_batch_case (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id     BIGINT    NOT NULL,
    case_id      BIGINT    NOT NULL,
    case_version INT       NOT NULL DEFAULT 1,
    sort_order   INT       NOT NULL DEFAULT 0,
    result       SMALLINT  NOT NULL DEFAULT 4,
    manual_skip  SMALLINT  NOT NULL DEFAULT 0,
    error_type   SMALLINT,
    duration_ms  INT,
    retry_cnt    INT       NOT NULL DEFAULT 0,
    flaky        SMALLINT  NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_case ON tp_batch_case (batch_id, case_id);
CREATE INDEX IF NOT EXISTS idx_batch_case_batch ON tp_batch_case (batch_id, sort_order);
COMMENT ON TABLE tp_batch_case IS '批次明细（I1 草稿成员集；启动后逐条版本锁定，01 ； O9 注记「批次明细=tp_exec_task 行」为执行期视图，草稿期成员集落本表——两者经 task.batch_case_id 关联， 启动时物化）';

CREATE TABLE IF NOT EXISTS tp_batch_template (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT       NOT NULL,
    name        VARCHAR(200) NOT NULL,
    case_ids    JSONB        NOT NULL,
    env_policy  VARCHAR(20),
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_batch_template_project ON tp_batch_template (project_id);
COMMENT ON TABLE tp_batch_template IS '批次模板（I1；tp_exec_batch.template_id 来源列已在 迁移件，零加列）';
