INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.tool.confirm-ttl-seconds', '300', '系统', 'int', 0, '写语义工具确认票生效窗口秒（T168 包4；签发后凭票二次调用放行，过期作废，TC-U-04）')
ON CONFLICT (config_key) DO NOTHING;
