
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS embedding_config_id BIGINT;

COMMENT ON COLUMN tp_project.embedding_config_id IS '项目选用 embedding 模型（NULL=系统默认，G1/T174b 三级兜底首位候选端点；与 llm_config_id 生成选用分域）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.embedding.batch-size', '128', '系统', 'int', 0, 'embedding 向量化批量大小（G1/T174b；消费方=T176 入库管线，区间 1~1024 越界回退配置文件）')
ON CONFLICT (config_key) DO NOTHING;
