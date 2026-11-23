
ALTER TABLE tp_user ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP NOT NULL DEFAULT now();
COMMENT ON COLUMN tp_user.password_changed_at IS
    '最近改密时间（R5 有效期起算点；DEFAULT now()=存量回填迁移日，首轮到期=迁移日+expire-days 不误伤=D4；改密/管理员重置自动刷新）';

ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS health_checked_at TIMESTAMP NULL;
ALTER TABLE tp_project_env ADD COLUMN IF NOT EXISTS health_msg VARCHAR(200) NULL;
COMMENT ON COLUMN tp_project_env.health_checked_at IS
    '最近一次健康检查完成时间（R1；NULL=从未检查，徽标显示「未知」灰态）';
COMMENT ON COLUMN tp_project_env.health_msg IS
    '异常原因摘要（R1 徽标 tooltip / 批次预检警告数据源；health_status=2 时有值，恢复健康置 NULL）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('password.expire-days',        '90', '安全', 'int', 0, '密码有效天数（R5；到期登录强制改密，0=永不过期）'),
    ('env.health-check.enabled',    '1',  '安全', 'int', 0, '环境健康检查开关（R1；0=停用检查任务，徽标语义退化为未知）'),
    ('env.health-interval-minutes', '15', '安全', 'int', 0, '环境健康检查间隔分钟（R1；批次预检「近 N 分钟异常」窗口同源）')
ON CONFLICT (config_key) DO NOTHING;
