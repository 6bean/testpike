
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS task_type SMALLINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN tp_exec_task.task_type IS '任务类型：1=用例执行 2=工具试跑（T167-b；case_id=0 哨兵，claim 下发供执行器分流）';

CREATE INDEX IF NOT EXISTS idx_exec_task_tool_pending ON tp_exec_task (id) WHERE task_type = 2 AND status = 1;
