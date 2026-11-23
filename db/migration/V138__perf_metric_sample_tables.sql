
CREATE TABLE IF NOT EXISTS tp_perf_metric (
    id             BIGSERIAL    PRIMARY KEY,
    run_id         BIGINT       NOT NULL REFERENCES tp_perf_run (id),
    project_id     BIGINT       NOT NULL REFERENCES tp_project (id),
    second_offset  INT          NOT NULL,
    rps            NUMERIC(12,3) NOT NULL,
    p95_ms         NUMERIC(12,3) NOT NULL,
    error_rate     NUMERIC(6,5)  NOT NULL,
    ttft_ms        NUMERIC(12,3),
    itl_ms         NUMERIC(12,3),
    tokens_per_sec NUMERIC(12,3),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_perf_metric_rate CHECK (error_rate >= 0 AND error_rate <= 1),
    CONSTRAINT ck_perf_metric_offset CHECK (second_offset >= 0),
    CONSTRAINT uq_perf_metric_frame UNIQUE (run_id, second_offset)
);

CREATE INDEX IF NOT EXISTS ix_perf_metric_run ON tp_perf_metric (run_id, second_offset);

COMMENT ON TABLE tp_perf_metric IS '压测秒级指标帧（三线=吞吐/响应/错误率；AI 推理族 TTFT/ITL/tokenPerSec 一等字段可空=非 AI 场景；run×秒唯一=回传重放幂等锚）';

CREATE TABLE IF NOT EXISTS tp_perf_sample (
    id            BIGSERIAL    PRIMARY KEY,
    run_id        BIGINT       NOT NULL REFERENCES tp_perf_run (id),
    project_id    BIGINT       NOT NULL REFERENCES tp_project (id),
    second_offset INT          NOT NULL,
    event_kind    VARCHAR(40)  NOT NULL,
    detail        VARCHAR(4000) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_perf_sample_run ON tp_perf_sample (run_id, id);

COMMENT ON TABLE tp_perf_sample IS '压测事件流样本（熔断触发/worker 失联/异常 traceback 摘要等，运行页日志抽屉消费面；detail 体积闸 4000 字符=执行器侧摘要后回传）';
