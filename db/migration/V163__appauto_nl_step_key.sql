
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.appauto.nl-step', '1', 'APP自动化', 'int', 0,
     'APP 自然语言步骤生成开关（默认关；关=一句中文转步骤恒拒零外呼，开=描述经目标引擎能力位图裁剪生成 app.* IR 步骤草稿，用户确认后并入步骤序列。用例编辑区 App 步骤消费）')
ON CONFLICT (config_key) DO NOTHING;
