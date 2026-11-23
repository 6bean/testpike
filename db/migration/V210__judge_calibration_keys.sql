
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('judge.calibration.kappa-threshold', '0.6', 'AI评测', 'string', 0, '人机一致率 Cohen''s κ 达标线（滚动 κ 跌破=自动升档双裁判，域 0~1 越界/脏值回退默认 0.6，热更即生效）'),
('judge.calibration.pass-threshold', '60', 'AI评测', 'int', 0, '一致率统计判合格双档切档线（机器分/人工分各自 ≥该值记 pass 档算 κ，0~100 越界/脏值回退默认 60，热更即生效）'),
('judge.calibration.bootstrap-samples', '200', 'AI评测', 'int', 0, '校准基线所需改判配对数（未达=全量双裁判建基线，≥1 越界/脏值回退默认 200，热更即生效）'),
('judge.calibration.sentinel-rate', '0.02', 'AI评测', 'string', 0, '稳态漂移哨兵随机抽样率（边界优先之外的分层随机补样，域 0~1 越界/脏值回退默认 0.02，热更即生效）'),
('judge.calibration.boost-rate', '0.25', 'AI评测', 'string', 0, '一致率跌破后升档双裁判抽样率（滚动 κ 跌破达标线自动升到该比例直至重新稳定，域 0~1 越界/脏值回退默认 0.25，热更即生效）'),
('judge.calibration.boundary-band', '5', 'AI评测', 'int', 0, '准出门槛临界带宽分（±1 档落键；|合成分-切档线|≤该带宽=会翻 pass/fail 的边界样本稳态全量双裁判，≥0 越界/脏值回退默认 5，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
