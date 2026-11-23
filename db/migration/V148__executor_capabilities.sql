
ALTER TABLE tp_executor ADD COLUMN IF NOT EXISTS capabilities JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN tp_executor.capabilities IS '执行器自探测能力面（03 协议 2.1 register 尾追键落库；{}=旧执行器未上报）';
