
ALTER TABLE tp_exec_batch ADD COLUMN IF NOT EXISTS browser VARCHAR(20);

COMMENT ON COLUMN tp_exec_batch.browser IS '批次统一执行浏览器（H3 尾：chromium/firefox/webkit 单值，NULL=未指定按缺省；建批按环境矩阵 fail-closed 校验，启动透传任务级）';
