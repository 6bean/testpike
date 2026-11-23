-- Streaming inter-line idle bound (K8 hot key). When a chat-completion stream
-- stays silent between SSE lines longer than this value, the call is cut and
-- reported as a model timeout instead of waiting out the whole exchange budget.
-- Range 5~300s enforced on the core save side; default 90s sits above every
-- observed healthy provider gap.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('ai.llm.stream-idle-timeout-seconds', '90', '模型', 'int', 0,
        '流式响应线间静默上限秒数（默认 90，区间 5~300；超过即按模型超时中断本次调用）')
ON CONFLICT (config_key) DO NOTHING;
