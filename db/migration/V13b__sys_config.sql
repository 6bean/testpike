-- ============================================================
-- ============================================================
CREATE TABLE IF NOT EXISTS tp_sys_config (
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(100) NOT NULL,
    config_value TEXT         NOT NULL,
    group_name   VARCHAR(50)  NOT NULL DEFAULT 'system',
    value_type   VARCHAR(20)  NOT NULL DEFAULT 'string',  -- string/int/bool/json
    encrypt_flag SMALLINT     NOT NULL DEFAULT 0,
    description  VARCHAR(500),
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_sys_config_key UNIQUE (config_key)
);
COMMENT ON TABLE tp_sys_config IS '系统配置（K8，管理员 CRUD，变更审计）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('recording.retention.days',  '90',    '系统', 'int',  0, '录屏保留期（9.3.4 默认 90 天）'),
    ('review.comment.required',   'false', '系统', 'bool', 0, '审批备注是否必填（5.4）'),
    ('case.timeout.minutes',      '10',    '系统', 'int',  0, '单条用例超时阈值（I4 默认 10 分钟）'),
    ('executor.concurrency',      '4',     '系统', 'int',  0, '批次并发配额（H4）'),
    ('ai.repair.rounds',          '3',     '系统', 'int',  0, '修复轮次上限（E8 默认 3）'),
    ('ai.context.watermark',      '70',    '系统', 'int',  0, '上下文压缩水位（E8 默认 70%）'),
    ('backlog.warn.days',         '3',     '系统', 'int',  0, '积压预警阈值（4.8 默认 3 天）'),
    ('flaky.threshold',           '3',     '系统', 'int',  0, 'flaky 阈值（9.3.3 默认连续 3 次）'),
    ('security.log.retention.months', '6', '系统', 'int',  0, '操作日志保留时长（6.2 默认 6 个月）'),
    ('security.screen.mask.inputs', 'password,secret,token,apiKey', '安全', 'json', 0, '录屏脱敏输入类型（9.3.4）'),
    ('integration.jira.webhook.secret', '', '通知', 'string', 1, 'Jira webhook 签名密钥（加密存储）')
ON CONFLICT (config_key) DO NOTHING;
