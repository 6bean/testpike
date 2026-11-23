
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.doc.token-budget', '20000', '系统', 'int', 0, 'F5 文档提取上下文 token 预算（超预算分组摘要压缩，14 §3.1；V29）')
ON CONFLICT (config_key) DO NOTHING;
