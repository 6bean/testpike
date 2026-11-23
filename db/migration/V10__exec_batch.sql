-- =====================================================================
-- =====================================================================
CREATE TABLE IF NOT EXISTS tp_exec_batch (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id             BIGINT       NOT NULL,
    env_id                 BIGINT,
    batch_no               VARCHAR(40),
    name                   VARCHAR(200),
    status                 SMALLINT     NOT NULL DEFAULT 1,
    case_version_snapshot  JSONB,
    env_version_snapshot   VARCHAR(100),
    type                   SMALLINT     NOT NULL DEFAULT 1,
    template_id            BIGINT,
    queue_pos              INT,
    schedule_type          SMALLINT     NOT NULL DEFAULT 0,
    created_by             BIGINT,
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    started_at             TIMESTAMP,
    finished_at            TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_exec_batch_project ON tp_exec_batch (project_id, id);
