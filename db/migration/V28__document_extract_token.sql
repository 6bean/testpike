
ALTER TABLE tp_document
    ADD COLUMN IF NOT EXISTS extract_token VARCHAR(64);

COMMENT ON COLUMN tp_document.extract_token IS
    'F2 提取任务轮次 token（UUID，markExtracting 时生成；ai-service 回写带 token，core 守卫 status=3 AND token 匹配——旧轮次回写 409 忽略，T131 补测包 tick 71）';
