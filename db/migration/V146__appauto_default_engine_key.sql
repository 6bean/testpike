
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.default-engine', 'MOBILEWRIGHT', 'APP自动化', 'string', 0,
     'App 自动化默认引擎（未显式选引擎时消费；值域=引擎描述符目录启用面 code，非法/已下架回退内置 Mobilewright）')
ON CONFLICT (config_key) DO NOTHING;
