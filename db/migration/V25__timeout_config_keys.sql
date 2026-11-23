
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('exec.case-timeout-minutes',  '60', '系统', 'int', 0, 'I4 单条任务超时分钟（超时扫描置 5 + 释放占用，TC-I4-01）'),
('exec.batch-timeout-hours',   '4',  '系统', 'int', 0, 'I4 批次总超时小时（看门狗到点置为已取消，10 §S8）')
ON CONFLICT (config_key) DO NOTHING;

DELETE FROM tp_sys_config WHERE config_key = 'case.timeout.minutes';
