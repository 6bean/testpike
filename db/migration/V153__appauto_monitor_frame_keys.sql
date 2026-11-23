INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('appauto.monitor.frame-ttl-seconds', '60', '系统', 'int', 0, '监看帧环形缓冲保活秒（每次收帧刷新；监看会话断流超窗自动清空，防残留旧帧误导画面）'),
('appauto.monitor.frame-max', '10', '系统', 'int', 0, '监看帧环形缓冲最大条数（超限丢最旧并计数透出，前端轮询只取最新面）'),
('appauto.monitor.frame-max-bytes', '262144', '系统', 'int', 0, '单帧图像上限字节（base64 内联帧体积闸，超限诚实丢弃计入降级数禁静默）')
ON CONFLICT (config_key) DO NOTHING;
