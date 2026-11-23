
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('judge.llm.internal-providers', '', 'AI评测', 'string', 0, '内部部署模型厂商白名单（逗号分隔忽略大小写；sensitive=1 样本禁送白名单外厂商裁判，空=一律禁送外部，热更即生效）'),
('judge.llm.allow-same-provider', '0', 'AI评测', 'int', 0, '同厂商禁配豁免位（0=默认禁（自我偏置防线）1=豁免派发留痕；非法值按 0 保守处理，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
