
CREATE TABLE IF NOT EXISTS tp_exec_step_snapshot (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id             BIGINT       NOT NULL REFERENCES tp_exec_task(id) ON DELETE CASCADE,
    step_no             INT          NOT NULL,
    kind                VARCHAR(40)  NOT NULL,
    truncated           SMALLINT     NOT NULL DEFAULT 0,
    raw_bytes           BIGINT       NOT NULL DEFAULT 0,
    request_snapshot    BYTEA,
    response_snapshot   BYTEA,
    assertions_snapshot BYTEA,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_exec_step_snapshot UNIQUE (task_id, step_no)
);

COMMENT ON TABLE tp_exec_step_snapshot IS '执行步骤协议快照（V1）：步级请求/响应/断言 gzip 落库，随保留策略 CASCADE 清理';
COMMENT ON COLUMN tp_exec_step_snapshot.truncated IS '1=正文超 exec.snapshot.max-bytes 预算已截断（尾部含 [truncated] 标记）';
COMMENT ON COLUMN tp_exec_step_snapshot.raw_bytes IS '截断前（掩码后）三列 JSON UTF-8 原体积合计字节';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('exec.snapshot.max-bytes', '262144', '系统', 'int', 0, '协议快照单步体积预算（V1/T229；默认 262144=256KB，域 1024~10485760 写侧服务层校验，超限步打截断标记；TC-V1-01/IT-V1-01）')
ON CONFLICT (config_key) DO NOTHING;
