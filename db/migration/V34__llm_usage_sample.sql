
CREATE TABLE IF NOT EXISTS tp_llm_usage_sample (
    id                    BIGSERIAL PRIMARY KEY,
    llm_config_id         BIGINT,
    provider              VARCHAR(50)  NOT NULL,
    model                 VARCHAR(100) NOT NULL,
    chain                 VARCHAR(30)  NOT NULL,
    project_id            BIGINT,
    est_tokens            INTEGER      NOT NULL,
    usage_prompt_tokens   INTEGER      NOT NULL,
    completion_tokens     INTEGER      NOT NULL DEFAULT 0,
    deviation_rate        NUMERIC(8,4) GENERATED ALWAYS AS
        ((usage_prompt_tokens - est_tokens)::numeric / nullif(usage_prompt_tokens, 0)) STORED,
    latency_ms            INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_usage_sample_model ON tp_llm_usage_sample (model, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_sample_created ON tp_llm_usage_sample (created_at);
COMMENT ON TABLE tp_llm_usage_sample IS 'Token 校准采样（E8 ，观测面异步写、失败不阻断主链）';
