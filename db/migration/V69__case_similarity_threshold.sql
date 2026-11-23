
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('case.similarity.hint-threshold',       '0.80', '用例', 'string', 0, 'A9 相似提示线（T180 14 §5.3；≥该值=提示「与 TC-xxx 相似」；域 0~1 越界/脏值回退 0.80）'),
('case.similarity.strong-hint-threshold','0.90', '用例', 'string', 0, 'A9 强提示线（T180 14 §5.3；≥该值=强提示+差异展示；低于提示线=倒挂两线同回退默认）')
ON CONFLICT (config_key) DO NOTHING;
