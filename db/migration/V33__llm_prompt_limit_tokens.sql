
ALTER TABLE tp_llm_config
    ADD COLUMN IF NOT EXISTS prompt_limit_tokens INTEGER NOT NULL DEFAULT 1048576;

COMMENT ON COLUMN tp_llm_config.prompt_limit_tokens IS
    'E8 v2.14 提示词 token 上限（PromptSizeGuard 判定，默认 1M=1048576，校验 1024~10000000）';
