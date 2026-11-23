ALTER TABLE tp_exec_batch    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE tp_notification  ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE tp_project_env   ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_exec_batch.version   IS '乐观锁版本号（E10：状态变更 version+1，并发冲突 40901 可读；存量行=0）';
COMMENT ON COLUMN tp_notification.version IS '乐观锁版本号（E10： 补列；markRead 条件更新天然幂等，version 仅状态变更路径 bump）';
COMMENT ON COLUMN tp_project_env.version  IS '乐观锁版本号（E10：环境编辑 version+1，并发冲突 40901；原登记名 tp_environment 勘误）';
