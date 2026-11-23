
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('tls.exit-plan.reject-insecure', '0', '安全', 'bool', 0,
        '准出计划禁含跳过证书校验（insecure）的执行：1=批次启动预检直接拒单，0=停用（默认）')
ON CONFLICT (config_key) DO NOTHING;
