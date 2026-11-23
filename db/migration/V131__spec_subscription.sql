
CREATE TABLE IF NOT EXISTS tp_spec_subscription (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id             BIGINT        NOT NULL REFERENCES tp_project (id),
    name                   VARCHAR(200)  NOT NULL,
    url                    VARCHAR(1000) NOT NULL,
    check_interval_minutes INT           NOT NULL DEFAULT 1440,
    enabled                BOOLEAN       NOT NULL DEFAULT TRUE,
    last_spec_id           BIGINT,
    last_status            VARCHAR(16),
    last_error             VARCHAR(500),
    last_check_at          TIMESTAMPTZ,
    created_by             BIGINT,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_spec_subscription_project_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS ix_spec_subscription_due
    ON tp_spec_subscription (enabled, last_check_at);

COMMENT ON TABLE tp_spec_subscription IS '契约订阅源：定时按 URL 重拉进版本链（07 事件表 SPEC_CHANGE_DETECTED）';
COMMENT ON COLUMN tp_spec_subscription.last_status IS '最近一轮扫描结局：updated=产新版本/unchanged=内容一致409/failed=拉取或解析失败(last_error)';
