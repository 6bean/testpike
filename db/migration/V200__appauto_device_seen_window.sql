
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('appauto.device-seen-window-seconds', '300', 'APP自动化', 'int', 0, '设备探活上报最大允许空窗秒（T333 执行前置健康闸：last_seen_at 缺失或超窗=判离线，派发前秒拒不烧会话；缺省 300，非法/缺键回退缺省，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
