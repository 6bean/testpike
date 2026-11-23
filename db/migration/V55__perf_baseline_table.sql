
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_perf_baseline (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    endpoint    VARCHAR(200)  NOT NULL,
    run_at      TIMESTAMP     NOT NULL,
    samples     INT           NOT NULL,
    p50_ms      DOUBLE PRECISION NOT NULL,
    p95_ms      DOUBLE PRECISION NOT NULL,
    p99_ms      DOUBLE PRECISION NOT NULL,
    max_ms      DOUBLE PRECISION NOT NULL,
    phase       VARCHAR(16)   NOT NULL DEFAULT 'before',
    note        VARCHAR(500),
    created_at  TIMESTAMP     NOT NULL DEFAULT now()
);
COMMENT ON TABLE  tp_perf_baseline IS 'K13 性能基线 trend（v2.26 T207；append 行，每轮全量回归一次；无基线不优化红线的数据面）';
COMMENT ON COLUMN tp_perf_baseline.endpoint IS 'METHOD + 归一路径（实 ID 折 {id}），Top15 清单枚举同源';
COMMENT ON COLUMN tp_perf_baseline.samples   IS '有效样本数；<100 时工装标 INSUFFICIENT 且不写本表（IT-K13-02）';
COMMENT ON COLUMN tp_perf_baseline.phase     IS 'before=基线建档（T207）/ after=优化后对比（T208）';

CREATE INDEX IF NOT EXISTS idx_perf_baseline_endpoint_run
    ON tp_perf_baseline (endpoint, run_at);

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_exec_task_project_created
    ON tp_exec_task (project_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_call_log_model
    ON tp_ai_call_log (model);
