
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('auth.access-ttl-seconds', '1800', '安全', 'int', 0, '访问令牌有效秒数（R6 双 token；缩短自单一 7200s，过期由前端单飞刷新无感续期，TC-R6-01 测试可配短 TTL）'),
    ('auth.refresh-ttl-days',   '7',    '安全', 'int', 0, '刷新令牌有效天数（R6；轮换制：每次刷新旧失效新签发，重放检测=吊销该用户全部会话）')
ON CONFLICT (config_key) DO NOTHING;
