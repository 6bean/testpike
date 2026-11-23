
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('appauto.lease-ttl-explore', '3600', 'APP自动化', 'int', 0, 'App 探索会话设备占用窗秒（T330；kind=explore 档独立热更，缺失/非法回退 appauto.device-lease-ttl-seconds→lease.lease-ttl-seconds→默认恒等；长会话不污染常规档）'),
('ai.explore.appauto', 'true', '系统', 'bool', 0, 'App 探索测试总开关（T330；默认关=零外呼零残留，开启后允许移动形态探索会话创建与派发）')
ON CONFLICT (config_key) DO NOTHING;
