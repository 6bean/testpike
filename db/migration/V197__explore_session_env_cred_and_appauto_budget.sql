
ALTER TABLE tp_explore_session ADD COLUMN IF NOT EXISTS env_id BIGINT;
ALTER TABLE tp_explore_session ADD COLUMN IF NOT EXISTS cred_ref VARCHAR(64);

COMMENT ON COLUMN tp_explore_session.env_id IS '移动形态目标环境 id（tp_project_env；AppautoExploreAccessGate 白名单核对落列持久化，web 形态 NULL）';
COMMENT ON COLUMN tp_explore_session.cred_ref IS '测试账号凭据引用（tp_credential.cred_key 键名非 id；{{CRED:键}} 占位单源，明文禁入 prompt=红线4）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.explore.appauto.max-steps', '256', '系统', 'int', 0, 'App 移动探索单次会话最大步数（T330 三重预算之步数线；创建时刻入快照定版，热改只影响新会话；web 支 ai.explore.max-steps 分线禁混）'),
('ai.explore.appauto.max-tokens', '1000000', '系统', 'int', 0, 'App 移动探索单次会话 token 预算上限（T330 三重预算之 token 线；计量分线 function=explore-appauto 禁混 web 支）'),
('ai.explore.appauto.max-duration-seconds', '3600', '系统', 'int', 0, 'App 移动探索单次会话墙钟时长上限秒（T330 三重预算之时长线；与 appauto.lease-ttl-explore 占用窗独立可调）')
ON CONFLICT (config_key) DO NOTHING;
