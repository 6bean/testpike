
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('cluster.upgrade-cooldown-seconds', '300', 'APP自动化', 'int', 0,
     '升级流水线批间冷却秒（默认 5 分钟；调 0=批间不等待，供演练与快速验证）'),
    ('cluster.canary-timeout-seconds', '1800', 'APP自动化', 'int', 0,
     '金丝雀套件执行超时秒（默认 30 分钟；超时=自动刹车并回滚灰度机）'),
    ('cluster.batch-timeout-seconds', '3600', 'APP自动化', 'int', 0,
     '升级单批执行超时秒（默认 1 小时；超时=自动刹车，在跑步不打断按失败归因）'),
    ('cluster.canary-pass-rate-min', '90', 'APP自动化', 'int', 0,
     '金丝雀通过率下限百分比（0-100 整数，默认 90；通过率低于此线=自动刹车+灰度机回滚+写明原因）'),
    ('cluster.retention-versions', '3', 'APP自动化', 'int', 0,
     '升级回滚保留上 N 版（默认 3；保留清单落发布清单保钉列，磁盘水位超限告警随装机工具箱消费）')
ON CONFLICT (config_key) DO NOTHING;
