
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.doc.summary-ratio',        '0.20', '系统', 'string', 0, 'E8 摘要预算=组预算×比例（0.05~0.9，v2.14 V32）'),
('ai.doc.summary-min-tokens',   '300',  '系统', 'int',   0, 'E8 摘要预算 token 下限（50~20000 且 ≤上限，v2.14 V32）'),
('ai.doc.summary-max-tokens',   '4000', '系统', 'int',   0, 'E8 摘要预算 token 上限（50~20000 且 ≥下限，v2.14 V32）'),
('ai.doc.reduce-trigger-groups','12',    '系统', 'int',   0, 'E8 逐级归并触发组数（2~64，v2.14 V32）'),
('ai.doc.reduce-max-levels',    '4',    '系统', 'int',   0, 'E8 归并层级上限（1~3，v2.14 V32）')
ON CONFLICT (config_key) DO NOTHING;
