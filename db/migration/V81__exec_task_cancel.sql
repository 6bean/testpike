ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS cancel_notified_at TIMESTAMP;
COMMENT ON COLUMN tp_exec_task.cancel_notified_at IS '终止指令已下发执行器时间（迁移件：status=7 且本列 NULL=待下发，心跳置位恰一次）';
