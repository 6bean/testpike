
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('appauto.takeover.input-ttl-seconds', '60', 'APP自动化', 'int', 0, '设备接管输入队列保活秒（T332；末次写入起算超窗自动清空残留输入防会话外注入，热更即生效，缺失/非法回退默认 60）'),
('appauto.takeover.input-queue-max', '50', 'APP自动化', 'int', 0, '单接管在途输入指令排队上限（T332；网页输入洪峰闸，超限整批拒绝并说明原因禁部分入账，热更即生效，缺失/非法回退默认 50）')
ON CONFLICT (config_key) DO NOTHING;
