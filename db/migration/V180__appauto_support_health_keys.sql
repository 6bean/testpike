
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.support-health-window-days', '7', 'APP自动化', 'int', 0,
     '引擎支持度健康维窗口天数（默认 7；引擎中心支持度抽屉「近 N 天通过率」的 N，调大=更长观察窗）'),
    ('appauto.support-health-pass-rate-min', '80', 'APP自动化', 'int', 0,
     '引擎支持度健康维通过率跌破线百分比（0-100 整数，默认 80；低于线=黄徽章+先看归因建议，观察建议线与金丝雀刹车线独立）'),
    ('ai.appauto.upgrade-advisor', '1', 'APP自动化', 'int', 0,
     'U-adv 升级顾问开关（默认关；关=顾问口 403 拒绝并说明原因零模型外呼，开=manifest 破坏性变更×用例特征画像出升级风险清单，消费位随支持度批 AI 包接线）'),
    ('ai.appauto.upgrade-window', '1', 'APP自动化', 'int', 0,
     'U-auto 智能排窗开关（默认关；关=排窗口 403 拒绝并说明原因零模型外呼，开=计划/批次忙闲→推荐窗口+灰度切法，只推荐不落权，与在跑计划冲突红线拒答）')
ON CONFLICT (config_key) DO NOTHING;
