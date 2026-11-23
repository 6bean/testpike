
CREATE TABLE IF NOT EXISTS tp_tool_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tool_id       BIGINT       NOT NULL REFERENCES tp_tool(id) ON DELETE CASCADE,
    version       INT          NOT NULL,
    body          TEXT,
    input_schema  JSONB,
    output_schema JSONB,
    exec_spec     JSONB,
    timeout_ms    INT,
    status_at_snapshot SMALLINT NOT NULL,
    reason        VARCHAR(500),
    created_by    BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_tool_ver UNIQUE (tool_id, version)
);
CREATE INDEX IF NOT EXISTS idx_tool_ver_tool ON tp_tool_version (tool_id, version DESC);
COMMENT ON TABLE tp_tool_version IS '工具版本链（UNIQUE(tool_id,version)；覆盖前旧值五件套快照，append-only）';
