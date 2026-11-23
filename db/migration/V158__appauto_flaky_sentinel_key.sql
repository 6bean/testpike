
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.appauto.flaky-sentinel', '1', 'APP自动化', 'int', 0,
     'APP flaky 哨兵 AI 建议翻译开关（默认关；关=用例×设备稳定性分纯统计判定零模型外呼，开=翻转命中的组追加修复建议（换等待策略/换定位通道）。引擎中心→用例稳定性看板消费）')
ON CONFLICT (config_key) DO NOTHING;
