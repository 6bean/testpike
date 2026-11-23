
ALTER TABLE tp_export_task ALTER COLUMN project_id DROP NOT NULL;
COMMENT ON COLUMN tp_export_task.project_id IS
    '所属项目（报表导出必填；R3 链校验异步任务=系统级 NULL，NULL 不受项目 purge 级联删除）';

ALTER TABLE tp_export_task ADD COLUMN IF NOT EXISTS result_json JSONB;
COMMENT ON COLUMN tp_export_task.result_json IS
    '异步任务结果快照（R3 链校验：{"valid":bool,"brokenAtId":long|null,"checkedCount":long}；报表导出为 NULL）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('audit.chain-verify.async-threshold-rows', '100000', '安全', 'int', 0,
     '审计链校验异步阈值行数（R3；链超此行数走导出任务队列异步校验，PRD 默认 10 万）')
ON CONFLICT (config_key) DO NOTHING;
