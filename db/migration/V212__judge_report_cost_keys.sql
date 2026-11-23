
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('judge.cost.price-per-1k-tokens', '0', 'AI评测', 'string', 0, '裁判 LLM token 牌价（元/千 token；缺省 0=未设牌价只报 token 实耗禁编造金额，脏值/负值按未设处置，热更即生效）'),
('judge.report.diff-alpha', '0.05', 'AI评测', 'string', 0, '两运行均值差显著性判定水平 α（Welch 双侧 p < α 记显著，域 0~1 越界/脏值回退默认 0.05，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
