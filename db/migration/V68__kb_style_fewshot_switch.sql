
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.kb.style-fewshot.enabled', 'true', '知识库', 'bool', 0, 'A4 风格适配 few-shot 注入开关（G2/T179；true=生成时按 CASE_GEN 检索优秀用例注入 user prompt，false=零注入默认安全）')
ON CONFLICT (config_key) DO NOTHING;
