
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS ai_failure_class SMALLINT;
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS ai_failure_note  VARCHAR(500);
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS ai_classified_at TIMESTAMPTZ;
ALTER TABLE tp_project   ADD COLUMN IF NOT EXISTS classify_vision  SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_exec_task.ai_failure_class IS 'AI 失败归因三分类 1=产品缺陷 2=AI定位错误 3=环境问题（C3/T251；NULL=未归类；与执行器 error_type 四分类正交）';
COMMENT ON COLUMN tp_exec_task.ai_failure_note  IS 'AI 归因一句话说明（可读展示源；fail-soft 不阻断主链）';
COMMENT ON COLUMN tp_exec_task.ai_classified_at IS 'AI 归因完成时间（重生成期同错误复用判定源）';
COMMENT ON COLUMN tp_project.classify_vision    IS '失败归因 vision 增强开关 0=关(默认) 1=开（IT-C3-01 成本闸，按项目配）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('ai.classify.enabled', '1', '系统', 'int', 0, 'C3 失败归因主开关（T251；默认 0=关：开关关=零外呼零落列 TC-C3-01；仅 0/1 写侧校验）'),
('ai.classify.model', '', '系统', 'string', 0, 'C3 归因模型名（T251；空=沿用 LLM 默认链；vision 增强按项目 classify_vision 键叠加）')
ON CONFLICT (config_key) DO NOTHING;
