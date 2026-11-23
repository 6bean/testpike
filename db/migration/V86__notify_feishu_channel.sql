
ALTER TABLE tp_project    ADD COLUMN IF NOT EXISTS notify_feishu SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_exec_batch ADD COLUMN IF NOT EXISTS notify_feishu SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_project.notify_feishu    IS '批次完成/失败飞书群通知开关 0=仅站内(默认) 1=开（V6/T237 定案D，批优先→项目回退）';
COMMENT ON COLUMN tp_exec_batch.notify_feishu IS '批次飞书群通知开关（批级覆写，1=强制发；0=回退项目级；V6/T237 定案D）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('notify.feishu.enabled', '0', '通知', 'int', 0, '飞书群机器人渠道主开关（V6/T237；默认 0=关存量零变化，仅 0 或 1 写侧校验；TC-V6-01）'),
('notify.feishu.webhook', '', '通知', 'string', 0, '飞书群机器人 webhook 地址（V6/T237；写侧 https 或 loopback 夹具面+SSRF 守卫双保险，读侧出站二次校验；IT-V6-01）'),
('notify.feishu.secret', '', '通知', 'string', 1, '飞书机器人签名密钥（V6/T237；加密存储 DB 无明文、回显打码；非空=出站携 timestamp+sign 头；IT-V6-01/IT-K8-02）'),
('notify.web.base-url', '', '通知', 'string', 0, '系统对外 URL（V6/T237，PRD 4.8 键名落定；飞书批次消息明细链接拼装源 base-url+/batches/{id}；空=明细链接段省略）')
ON CONFLICT (config_key) DO NOTHING;
