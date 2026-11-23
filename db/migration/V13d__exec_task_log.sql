
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS log TEXT;
COMMENT ON COLUMN tp_exec_task.log IS '执行器 console 日志（K2：页面 console 消息逐条捕获换行拼接，禁含凭据）';
