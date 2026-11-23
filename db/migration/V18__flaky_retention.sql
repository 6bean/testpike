
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS flaky SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS flaky_reason VARCHAR(200);
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS flaky_marked_at TIMESTAMP NULL;
COMMENT ON COLUMN tp_test_case.flaky IS 'Flaky 隔离标记（Q3；0=正常 1=已隔离，批次预检跳过）';
COMMENT ON COLUMN tp_test_case.flaky_reason IS '最近一次 flaky 判定理由（翻转次数/窗口，Q3）';
COMMENT ON COLUMN tp_test_case.flaky_marked_at IS 'flaky 标记时间（Q3；检测 ElasticJob / 一键隔离均写入）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('retention.video-days',          '90',  '系统', 'int', 0, '录屏保留天数（Q6，到期清理 tp_exec_task 录屏对象+行）'),
    ('retention.audit-days',          '365', '安全', 'int', 0, '审计日志保留天数（Q6，替代 v2.1 前固定 6 个月口径）'),
    ('retention.exec-history-days',   '180', '系统', 'int', 0, '执行历史保留天数（Q6）'),
    ('retention.training-sample-days','730', '系统', 'int', 0, '训练样本保留天数（Q6）')
ON CONFLICT (config_key) DO NOTHING;

DELETE FROM tp_sys_config WHERE config_key IN ('recording.retention.days', 'security.log.retention.months');
