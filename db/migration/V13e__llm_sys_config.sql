-- ============================================================
-- ============================================================
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('llm.provider',       '',    '模型', 'string', 0, '默认 LLM 供应商（openai/deepseek/...；空=回退环境变量，T56 K8/K9 联动）'),
    ('llm.base-url',       '',    '模型', 'string', 0, '默认 OpenAI 兼容端点基址（含 /v1；空=回退环境变量）'),
    ('llm.model',          '',    '模型', 'string', 0, '默认模型名（空=回退环境变量）'),
    ('llm.timeout-seconds', '300', '模型', 'int',    0, 'LLM 调用超时秒数（默认 300，区间 10~300；v2.15 C 契约）'),
    ('llm.api-key',        '',    '模型', 'string', 1, '默认 LLM 供应商密钥（加密存储，DB 无明文；空=回退环境变量）')
ON CONFLICT (config_key) DO NOTHING;
