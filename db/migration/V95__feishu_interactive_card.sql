
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('notify.feishu.card.enabled', '0', '通知', 'int', 0, '飞书交互卡片开关（E11/T252；默认 0=关存量零变化，1=三事件出 interactive 卡；仅 0/1 写侧校验；TC-E11-01）'),
('notify.feishu.callback.encrypt-key', '', '通知', 'string', 1, '飞书回调验签与已读回传令牌同密钥（E11/T252；加密存储 DB 无明文、回显打码；签发方=core，验签方=api 回调；缺省=不出已读按钮降级文本卡；IT-E11-01）')
ON CONFLICT (config_key) DO NOTHING;
