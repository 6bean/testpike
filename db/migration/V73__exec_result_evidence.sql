
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS result_ext JSONB;

COMMENT ON COLUMN tp_exec_task.result_ext IS '执行证据三键 ：{consoleViolations,netHits,dbDigest} 各≤20，三键全空=NULL';
