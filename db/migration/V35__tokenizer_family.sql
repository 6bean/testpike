ALTER TABLE tp_llm_config ADD COLUMN IF NOT EXISTS tokenizer_family VARCHAR(32) NOT NULL DEFAULT 'auto';
COMMENT ON COLUMN tp_llm_config.tokenizer_family IS 'E11 token 预估引擎族：auto/qwen/deepseek/cl100k/sp-char（默认 auto 按名推断）';
