-- Verification-window feed priority channel.
-- Batches flagged as window feeds route to a dedicated queue instead of
-- waiting behind the regular FIFO backlog; the flag is persisted per batch.
ALTER TABLE tp_ai_batch ADD COLUMN IF NOT EXISTS window_class SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN tp_ai_batch.window_class IS '0=regular feed, 1=verification-window feed (priority queue)';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.window-priority.enabled', 'true', '系统', 'bool', 0, '验证窗生成任务优先排队开关（默认开：标注为验证窗的批量任务进入独立优先队列，不排在常规积压之后；关闭=验证窗任务与常规任务同队列排队；修改即时生效，无需重启）'),
    ('ai.gen.window-priority.notice-backlog-threshold', '3', '系统', 'int', 0, '生成队列等待提示的积压条数阈值：队列中待处理任务达到该条数时，批次进度才显示等待原因与恢复预计；修改即时生效，无需重启'),
    ('ai.gen.window-priority.estimate-sample-hours', '24', '系统', 'int', 0, '恢复预计取样时长（小时）：按最近该时长内已完成生成任务的平均耗时估算排队等待；修改即时生效，无需重启'),
    ('ai.gen.window-priority.consumer-concurrency', '4', '系统', 'int', 0, '生成队列消费者并发数：用于估算排队恢复预计，需与生成服务的任务并发配置保持一致；修改即时生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
