ALTER TABLE tp_llm_usage_sample ADD COLUMN IF NOT EXISTS tokenizer_engine VARCHAR(64);
COMMENT ON COLUMN tp_llm_usage_sample.tokenizer_engine IS 'E11 事前估算引擎 ID（TokenEstimator.engineId；NULL=E8 基线 jtokkit-cl100k）';
