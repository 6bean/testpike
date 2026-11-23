
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.vision.enabled', '1', '系统', 'int', 0,
     '视觉模型总闸（默认关；关=视觉类 AI 应用点全部零调用零外呼原样报错，开=需分开关逐点点亮。上位闸与 appauto-locate 等分开关与运算）'),
    ('ai.vision.appauto-locate', '1', 'APP自动化', 'int', 0,
     'APP 步视觉定位兜底分开关（默认关；开=APP 自动化步确定性定位失败后经截图+视图树调视觉模型做三源共识定位，跑测详情时间线出「AI 定位」绿角标与定位证据包。引擎中心→跑测详情消费）'),
    ('ai.appauto.locate.confidence', '0.85,0.6', 'APP自动化', 'string', 0,
     '视觉定位置信阈值 hi,lo（缺省 0.85,0.6；≥hi 直点带角标，中间档点击后步内自检不一致停步落证据包，<lo 弃点给候选清单与改写建议；非法组合回退缺省）')
ON CONFLICT (config_key) DO NOTHING;
