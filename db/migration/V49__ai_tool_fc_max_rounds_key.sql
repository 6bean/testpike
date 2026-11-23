INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.tool.fc-max-rounds', '64', '系统', 'int', 0, 'ReAct 工具循环轮次上限（T168 消费；超限拒续取末次输出+带原因的告警，TC-U-03）')
ON CONFLICT (config_key) DO NOTHING;
