CREATE TABLE IF NOT EXISTS tp_api_spec (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT        NOT NULL,
    document_id     BIGINT,
    name            VARCHAR(200)  NOT NULL,
    spec_format     VARCHAR(32)   NOT NULL,
    spec_title      VARCHAR(200),
    spec_text       TEXT          NOT NULL,
    content_sha256  CHAR(64)      NOT NULL,
    spec_version    INT           NOT NULL DEFAULT 1,
    endpoints       JSONB         NOT NULL,
    prev_spec_id    BIGINT,
    diff_summary    JSONB,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_api_spec_format CHECK (spec_format IN ('openapi3-json', 'openapi3-yaml')),
    CONSTRAINT uq_api_spec_project_name_version UNIQUE (project_id, name, spec_version)
);
CREATE INDEX IF NOT EXISTS idx_api_spec_project_name
    ON tp_api_spec (project_id, name, spec_version DESC);
CREATE INDEX IF NOT EXISTS idx_api_spec_sha
    ON tp_api_spec (project_id, content_sha256);

COMMENT ON TABLE  tp_api_spec              IS 'F4 接口契约文档（OpenAPI/Swagger）版本留痕链：解析清单 + SHA256 diff';
COMMENT ON COLUMN tp_api_spec.document_id  IS '可空=不挂 tp_document（独立上传链路，白名单链零触碰）';
COMMENT ON COLUMN tp_api_spec.endpoints    IS 'JSONB 清单快照=[{method,path,endpointKey,operationId,title,parameters[],requestContentType,requestSchema,responseSchemas{},securityRefs[],structureHash}]';
COMMENT ON COLUMN tp_api_spec.diff_summary IS 'JSONB={added[],changed[],removed[]} 对照 prev_spec_id 的接口键三分类';
