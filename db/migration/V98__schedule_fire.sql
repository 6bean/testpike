
CREATE TABLE IF NOT EXISTS tp_schedule_fire (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind       VARCHAR(20)  NOT NULL,
    ref_id     BIGINT       NOT NULL,
    project_id BIGINT       NOT NULL,
    fire_time  TIMESTAMPTZ  NOT NULL,
    status     VARCHAR(10)  NOT NULL DEFAULT 'fired',
    message    TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_schedule_fire ON tp_schedule_fire (kind, ref_id, fire_time);
CREATE INDEX IF NOT EXISTS idx_schedule_fire_ref ON tp_schedule_fire (kind, ref_id, created_at DESC);

ALTER TABLE tp_batch_template ADD COLUMN IF NOT EXISTS cron_expr VARCHAR(100);
ALTER TABLE tp_batch_template ADD COLUMN IF NOT EXISTS enabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_batch_template ADD COLUMN IF NOT EXISTS schedule_env_id BIGINT;
