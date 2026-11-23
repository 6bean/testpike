
CREATE TABLE IF NOT EXISTS tp_apitest_run (
    id             bigserial PRIMARY KEY,
    request_id     bigint       NOT NULL REFERENCES tp_apitest_request (id),
    env_id         bigint,
    status         smallint     NOT NULL DEFAULT 0,
    duration_ms    bigint,
    assert_detail  jsonb        NOT NULL DEFAULT '[]'::jsonb,
    artifact_ref   varchar(500) NOT NULL DEFAULT '',
    error_msg      text         NOT NULL DEFAULT '',
    created_by     bigint,
    created_at     timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_apitest_run_request
    ON tp_apitest_run (request_id, created_at);
