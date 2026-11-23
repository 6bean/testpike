
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('tls.cert.expire-warn-days', '30', '安全', 'int', 0,
        '环境 HTTPS 证书到期前 N 天通知项目负责人（0=停用；证书观测链路每日 09:30 扫描到期窗）')
ON CONFLICT (config_key) DO NOTHING;
