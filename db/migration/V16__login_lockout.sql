
ALTER TABLE tp_user ADD COLUMN IF NOT EXISTS fail_count SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_user ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP NULL;
ALTER TABLE tp_user ADD COLUMN IF NOT EXISTS lock_reason VARCHAR(100);
COMMENT ON COLUMN tp_user.fail_count IS '登录连续失败次数（N1；成功登录/解锁/锁定过期自动清零）';
COMMENT ON COLUMN tp_user.locked_until IS '锁定截止时间（NULL=未锁定；N1 登录锁定）';
COMMENT ON COLUMN tp_user.lock_reason IS '锁定原因（连续 N 次密码错误/管理员，N1）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('testpike.login.max-fail',      '5',  '安全', 'int', 0, '登录连续失败锁定阈值（N1 默认 5 次）'),
    ('testpike.login.lock-minutes',  '15', '安全', 'int', 0, '登录锁定时长（N1 默认 15 分钟）')
ON CONFLICT (config_key) DO NOTHING;

UPDATE tp_data_pool_item SET status = CASE status WHEN 1 THEN 2 WHEN 2 THEN 1 ELSE 3 END;
ALTER TABLE tp_data_pool_item ADD COLUMN IF NOT EXISTS locked_by BIGINT;
ALTER TABLE tp_data_pool_item ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP NULL;
COMMENT ON COLUMN tp_data_pool_item.locked_by IS '占用任务 batch_case id（N2；1=空闲 2=占用中 3=已失效）';
COMMENT ON COLUMN tp_data_pool_item.locked_at IS '占用时间（N2；超时自动释放依据）';

ALTER TABLE tp_exec_batch ADD COLUMN IF NOT EXISTS skip_cases_json TEXT;
COMMENT ON COLUMN tp_exec_batch.skip_cases_json IS '启动时跳过的未评审用例清单 JSON（N6）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('testpike.notify.webhook-url',     '',   '通知', 'string', 0, 'webhook 渠道回调地址（N5，空=未配置）'),
    ('testpike.notify.webhook-enabled', 'false', '通知', 'bool', 0, 'webhook 渠道开关（N5）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('testpike.pool.lock-timeout-minutes', '30', '数据池', 'int', 0, '数据项占用超时自动释放阈值（N2，TC-N2-02 默认 30 分钟）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('testpike.batch.unevaluated-mode', 'skip', '批次', 'string', 0, '批次启动未通过评审用例处理：skip=跳过（默认）/ block=阻止（N6，TC-N6-01）')
ON CONFLICT (config_key) DO NOTHING;
