
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS browser VARCHAR(20) NOT NULL DEFAULT 'chromium';

COMMENT ON COLUMN tp_exec_task.browser IS '任务执行浏览器（H3 尾：chromium/firefox/webkit 单值，创建链路按环境 browsers 矩阵 fail-closed 校验，claim 下发执行器按内核 launch）';
