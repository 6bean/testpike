
ALTER TABLE tp_data_pool_item ADD COLUMN IF NOT EXISTS name VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_pool_project_env_name
    ON tp_data_pool_item (project_id, env_id, name)
    WHERE name IS NOT NULL;

COMMENT ON COLUMN tp_data_pool_item.name IS '数据键名（{{DATA:键}} 引用锚；项目+环境内唯一=部分索引；NULL=存量未补录不可引用，迁移件）';
