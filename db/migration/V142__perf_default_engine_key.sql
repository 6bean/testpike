INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.default-engine', 'GATLING', '系统', 'string', 0,
     '压测默认引擎（D1 选型：Gatling 默认/k6 备选；值域 K6/GATLING，非法值回退默认）')
ON CONFLICT (config_key) DO NOTHING;
