
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMP NULL;
COMMENT ON COLUMN tp_test_case.assigned_at IS
    '最近一次分配时间（Q4 催办起算点；分配/换人刷新=now，回池/清空置 NULL；存量行回填 updated_at）';

UPDATE tp_test_case SET assigned_at = updated_at
 WHERE assignee_id IS NOT NULL AND assigned_at IS NULL AND deleted = 0;

CREATE INDEX IF NOT EXISTS idx_test_case_assignee_assigned
    ON tp_test_case (assigned_at) WHERE deleted = 0 AND status IN (1, 2) AND assignee_id IS NOT NULL;

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('review.remind-hours',  '24', '系统', 'int', 0, '评审催办阈值小时（Q4；分配超此时长催办评审人，0=关闭该级）'),
    ('review.timeout-hours', '72', '系统', 'int', 0, '评审超时升级阈值小时（Q4；超此时长升级项目负责人，0=关闭该级）')
ON CONFLICT (config_key) DO NOTHING;
