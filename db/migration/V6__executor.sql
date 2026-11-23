
CREATE TABLE IF NOT EXISTS tp_executor (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL,
    group_ids       VARCHAR(200),
    env_ids         VARCHAR(200),
    end_types       VARCHAR(100)  DEFAULT 'web',
    browsers        VARCHAR(100)  DEFAULT 'chromium',
    capacity        INT           NOT NULL DEFAULT 2,
    running_cnt     INT           NOT NULL DEFAULT 0,
    status          SMALLINT      NOT NULL DEFAULT 1,
    version         VARCHAR(30),
    last_heartbeat  TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_executor_status ON tp_executor (status);

CREATE TABLE IF NOT EXISTS tp_exec_task (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT       NOT NULL,
    case_id          BIGINT       NOT NULL,
    batch_id         BIGINT,
    executor_id      BIGINT,
    status           SMALLINT     NOT NULL DEFAULT 1,
    claim_token      VARCHAR(64),
    schema_version   VARCHAR(10),
    action_json      TEXT,
    env_base_url     VARCHAR(500),
    error_type       SMALLINT,
    error_detail     VARCHAR(1000),
    steps_timeline   JSONB,
    screenshots_json JSONB,
    video_key        VARCHAR(200),
    duration_ms      INT,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_exec_task_status ON tp_exec_task (status, created_at);
CREATE INDEX IF NOT EXISTS idx_exec_task_case ON tp_exec_task (case_id);
