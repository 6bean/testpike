
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.matrix-fanout-max', '12', 'APP自动化', 'int', 0,
     '机型矩阵单用例扇出组合数上限（默认 12；扇出前成本诚实闸，超限拒绝并说明原因防烧穿设备池，调高即热更生效）'),
    ('ai.appauto.matrix-sample', '1', 'APP自动化', 'int', 0,
     '矩阵 AI 智能抽样开关（默认关；关=推荐口 403 拒绝并说明原因零模型外呼，开=覆盖率/成本画像推荐 Top-N 组合只建议人确认）')
ON CONFLICT (config_key) DO NOTHING;
