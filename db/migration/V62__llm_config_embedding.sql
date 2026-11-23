
ALTER TABLE tp_llm_config ADD COLUMN IF NOT EXISTS model_type SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE tp_llm_config ADD COLUMN IF NOT EXISTS embedding_dimension INTEGER;

ALTER TABLE tp_llm_config DROP CONSTRAINT IF EXISTS ck_llm_config_model_type;
ALTER TABLE tp_llm_config ADD CONSTRAINT ck_llm_config_model_type CHECK (model_type IN (1, 2));

CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_default_gen
    ON tp_llm_config (model_type) WHERE is_default = 1 AND model_type = 1;
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_default_emb
    ON tp_llm_config (model_type) WHERE is_default = 1 AND model_type = 2;

COMMENT ON COLUMN tp_llm_config.model_type IS '模型类型 1=生成 2=embedding（G1/；is_default 按类型分域唯一）';
COMMENT ON COLUMN tp_llm_config.embedding_dimension IS '向量维度（model_type=2 必填；向量表 vector(N) 建表固化锚，换模型必重建，14）';
