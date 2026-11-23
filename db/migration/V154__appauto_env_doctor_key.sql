
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.appauto.env-doctor', '1', 'APP自动化', 'int', 0,
     'AI 环境诊断开关（默认关；关=引擎体检只出确定性差集零 LLM，开=缺件项追加 AI 修复建议步骤含安装命令。引擎中心→体检按钮消费）')
ON CONFLICT (config_key) DO NOTHING;
