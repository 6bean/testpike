
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.device-quarantine-failures', '3', 'APP自动化', 'int', 0, '设备连续失联自动隔离阈值（T276a §5.3；缺键/非法回退默认 3）')
ON CONFLICT (config_key) DO NOTHING;
