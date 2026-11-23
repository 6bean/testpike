
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS jira_project_id        VARCHAR(30);
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS test_owner_id          BIGINT;
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS dev_owner_id           BIGINT;
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS script_storage_path    VARCHAR(300);
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS recording_storage_path VARCHAR(300);
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS storage_mode SMALLINT NOT NULL DEFAULT 1
    CONSTRAINT ck_project_storage_mode CHECK (storage_mode IN (1, 2, 3));

COMMENT ON COLUMN tp_project.jira_project_id IS ' J2 缺陷提交 key={jira_project_id}-{本地序号}；格式服务层校验 ';
COMMENT ON COLUMN tp_project.test_owner_id IS ' 测试负责人（tp_user.id 逻辑引用，服务层校验存在性；批次失败通知路由）';
COMMENT ON COLUMN tp_project.dev_owner_id IS ' 开发负责人（同上；缺陷默认分派）';
COMMENT ON COLUMN tp_project.script_storage_path IS ' K11 测试脚本存放路径（云=桶前缀/本地=目录；空=系统默认）';
COMMENT ON COLUMN tp_project.recording_storage_path IS ' K11 录制回放存放路径（同上）';
COMMENT ON COLUMN tp_project.storage_mode IS ' K11 存储模式 1=系统默认 2=本地 3=云（StorageRouter.route 消费）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.script.max-loop',            '64',   '系统', 'int',  0, '脚本生成 loop 上限（TC-AIS-02 注入 stage2 prompt+执行器校验；IT-AIS-01 区间服务层守）'),
('ai.script.max-steps',           '128',   '系统', 'int',  0, '脚本步骤数上限（超限 40905 可读「脚本步骤超限」+A6 修复循环；TC-AIS-01）'),
('ai.script.max-nest-depth',      '5',    '系统', 'int',  0, '动作 JSON 嵌套深度上限（04 契约合法面，TC-AIS-02）'),
('ai.script.selfheal-rounds',     '10',    '系统', 'int',  0, '执行器三级自愈轮次上限（A6 修复循环带上限）'),
('ai.script.assert-timeout-sec',  '10',   '系统', 'int',  0, '断言超时秒（IT-AIS-01 区间 [1,300] 服务层校验）'),
('ai.script.default-wait-sec',    '3',    '系统', 'int',  0, '默认等待秒（stage2 prompt 注入）'),
('ai.script.smart-wait',          'true', '系统', 'bool', 0, '智能等待开关（true=元素级等待替代固定 sleep）'),
('ai.script.auto-scroll',         'true', '系统', 'bool', 0, '自动滚动开关（操作前滚入视口）'),
('ai.script.screenshot-mode',     '3',    '系统', 'int',  0, '截图策略 1=每步 2=关键步 3=仅失败（默认仅失败）'),
('ai.script.fail-policy',         '1',    '系统', 'int',  0, '失败策略 1=继续后续步骤 2=立即中止（默认继续）')
ON CONFLICT (config_key) DO NOTHING;
