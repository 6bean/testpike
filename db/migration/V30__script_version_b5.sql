
CREATE TABLE IF NOT EXISTS tp_script_version (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id      BIGINT       NOT NULL,
    script_id    BIGINT       NOT NULL,
    case_version INT          NOT NULL,
    version      INT          NOT NULL,
    schema_version VARCHAR(10),
    actions_json JSONB,
    reason       VARCHAR(200),
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_script_version_case ON tp_script_version (case_id, case_version);
CREATE INDEX IF NOT EXISTS idx_script_version_script ON tp_script_version (script_id);
