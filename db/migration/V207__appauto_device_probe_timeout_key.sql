
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('appauto.device-probe-timeout-ms', '2000', 'APP自动化', 'int', 0, '远程 ADB 设备柜探活 TCP 握手超时毫秒（T335 周期 connect 探活：超窗=判失联，连续失联达 V144 阈值自动隔离；缺省 2000，非法/缺键回退缺省，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
