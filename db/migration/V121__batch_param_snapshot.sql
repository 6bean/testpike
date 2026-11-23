
CREATE TABLE IF NOT EXISTS tp_batch_param_snapshot (
    batch_id    bigint PRIMARY KEY REFERENCES tp_exec_batch (id),
    gen         integer NOT NULL DEFAULT 1,
    params_json text NOT NULL,
    created_at  timestamp NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tp_batch_runtime_change (
    id         bigserial PRIMARY KEY,
    batch_id   bigint NOT NULL REFERENCES tp_exec_batch (id),
    kind       varchar(16) NOT NULL,
    changed_at timestamp NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_batch_runtime_change_batch
    ON tp_batch_runtime_change (batch_id);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('batch.snapshot-ttl-hours',            '24',  '系统', 'int', 0, '批次参数快照 Redis 读穿缓存小时数（B2 丙快照钉版；DB 为权威面，键只管缓存窗）'),
('lock.env-exec-lease-seconds',         '120', '系统', 'int', 0, '环境执行互斥锁租期秒（看门狗按 lease/3 续租，进程崩溃后此值为死锁上限）'),
('notify.nonce-ttl-seconds',            '600', '系统', 'int', 0, '回调 nonce 防重放窗秒（须覆盖 ±5min 验签时间窗）'),
('ratelimit.callback-window-seconds',   '60',  '系统', 'int', 0, '飞书回调来源限流窗秒（固定窗口计数面）'),
('ratelimit.mcp-window-seconds',        '60',  '系统', 'int', 0, 'MCP 端点限流窗秒（固定窗口计数面）'),
('auth.pwd-attempt-window-minutes',     '15',  '系统', 'int', 0, '改密旧密码连续错误限流窗分钟（窗口内累计阈值拦截，成功改密清零）')
ON CONFLICT (config_key) DO NOTHING;
