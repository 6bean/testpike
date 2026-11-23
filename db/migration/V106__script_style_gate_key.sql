
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('script.style-gate.enabled', '0', '系统', 'int', 0,
 'B6 脚本规范闸总开关（0/1；开=codegen 来源保存拒违规点名+AI 生成链姿势重试，TC-B6-01）')
ON CONFLICT (config_key) DO NOTHING;
