-- ---------------------------------------------------------------------
--   docker exec testpike-postgres psql -U testpike -d testpike -f /dev/stdin < db/migration/V5__ai_task_notification.sql
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tp_ai_task (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL,
    operator_id      BIGINT NOT NULL,
    task_type        SMALLINT NOT NULL DEFAULT 1,
    status           SMALLINT NOT NULL DEFAULT 1,   -- 1=pending 2=running 3=done 4=failed
    requirement_text TEXT,
    requirement_id   BIGINT,
    case_id          BIGINT,
    script_id        BIGINT,
    error_code       VARCHAR(30),
    error_msg        VARCHAR(500),
    retry_cnt        INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_task_project ON tp_ai_task (project_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_task_operator ON tp_ai_task (operator_id, created_at);
COMMENT ON TABLE  tp_ai_task IS 'AI 生成任务（异步任务状态回写）';
COMMENT ON COLUMN tp_ai_task.status IS '1=pending 2=running 3=done 4=failed';
COMMENT ON COLUMN tp_ai_task.error_code IS 'MODEL_TIMEOUT/FORMAT_ERROR/SERVICE_ERROR';

CREATE TABLE IF NOT EXISTS tp_notification (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    project_id BIGINT,
    event_type VARCHAR(50) NOT NULL,
    channel    VARCHAR(20) NOT NULL DEFAULT '站内',
    title      VARCHAR(200) NOT NULL,
    content    TEXT,
    card_json  JSONB,
    status     SMALLINT NOT NULL DEFAULT 1,
    retry_cnt  INT NOT NULL DEFAULT 0,
    dedup_key  VARCHAR(100),
    read_flag  SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notification_user ON tp_notification (user_id, read_flag, created_at);
COMMENT ON TABLE tp_notification IS '站内信（生成失败/完成通知；07 通知中心设计）';
COMMENT ON COLUMN tp_notification.status IS '1=待发 2=已发 3=失败';
COMMENT ON COLUMN tp_notification.read_flag IS '0=未读 1=已读';
