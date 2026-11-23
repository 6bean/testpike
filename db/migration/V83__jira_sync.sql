
ALTER TABLE tp_defect ADD COLUMN IF NOT EXISTS jira_sync_status SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_defect ADD COLUMN IF NOT EXISTS jira_sync_error VARCHAR(500);

COMMENT ON COLUMN tp_defect.jira_sync_status IS 'JIRA 同步态 0=未同步/跳过 1=成功 2=失败待重试（V3/T233）';
COMMENT ON COLUMN tp_defect.jira_sync_error IS 'JIRA 出站失败留痕（≤500；成功清 NULL，V3/T233）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('jira.sync.enabled', '0', '系统', 'int', 0, 'JIRA 自动同步开关（V3/T233；默认 0=关存量零变化，仅 0 或 1 写侧校验；TC-V3-03/IT-V3-03）'),
('jira.sync.base-url', '', '系统', 'string', 0, 'JIRA 站点基址（V3/T233；如 https://jira.example.com，空=零出站；TC-V3-03）'),
('jira.sync.token', '', '系统', 'string', 1, 'JIRA API Token（V3/T233；Bearer 出站头，加密存储 DB 无明文；TC-V3-03/IT-K8-02）'),
('jira.sync.timeout-seconds', '5', '系统', 'int', 0, 'JIRA 出站超时秒（V3/T233；连接/读取预算热更随动，域 1~60 写侧校验；IT-V3-03）')
ON CONFLICT (config_key) DO NOTHING;
