INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('ai.batch.max-count', '500', '系统', 'int', 0, 'AI 批量生成单次上限（V8/T225；默认 500，域 1~500 写侧服务层校验，热更即时生效；TC-V8-01/IT-V8-01）')
ON CONFLICT (config_key) DO NOTHING;
