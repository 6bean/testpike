
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.appauto.step-healing', '1', 'APP自动化', 'int', 0,
     'APP 步骤自愈采纳闸开关（默认关；关=建议单人批采纳恒拒零写回，开=人批后候选定位合入脚本真实落版+回灌样本库。评审台→自愈建议单消费）')
ON CONFLICT (config_key) DO NOTHING;
