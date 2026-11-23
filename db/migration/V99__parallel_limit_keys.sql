
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('exec.parallel.max',         '0', '系统', 'int', 0, 'I5 全局在跑上限（0=不限；在跑≥上限新领排队，TC-I5-01）'),
('exec.parallel.per-project', '0', '系统', 'int', 0, 'I5 单项目在跑上限（0=不限；闸命中仅该候选排队，TC-I5-01）')
ON CONFLICT (config_key) DO NOTHING;
