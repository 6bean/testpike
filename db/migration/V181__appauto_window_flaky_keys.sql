
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.upgrade-window-hours', '24', 'APP自动化', 'int', 0,
     '智能排窗忙闲曲线槽数（默认 24；U-auto 逐时推荐面向未来 N 小时，调大=更长视野）'),
    ('appauto.upgrade-flaky-window-hours', '24', 'APP自动化', 'int', 0,
     '升级×flaky 观察窗小时数（默认 24；节点升级完成时刻前后各取该时长终态行对比通过率）'),
    ('appauto.upgrade-flaky-drop-pct', '30', 'APP自动化', 'int', 0,
     '升级×flaky 骤降判定线（0-100 整数，默认 30 个百分点；前后窗通过率差达线=出「疑似升级引入」归因行）')
ON CONFLICT (config_key) DO NOTHING;
