
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.vision.model', '', '系统', 'string', 0,
     '视觉定位兜底模型名（默认空=视觉定位请求拒绝并说明原因「视觉模型未配置」，禁回退文本模型假装视觉；填视觉模型名后经 ai.vision.enabled ∧ ai.vision.appauto-locate 双闸与运算生效）')
ON CONFLICT (config_key) DO NOTHING;
