
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.appauto.record-to-flow', '1', 'APP自动化', 'int', 0,
     'APP 录制转流程开关（默认关；关=录制草稿登记与勾选入库恒拒零外呼零写库，开=录制事件经反推生成 app.* IR 步骤草稿进两层质量门，人批勾选才入库。用例编辑区 App 步骤录制入口消费）'),
    ('ai.appauto.gen-steps', '1', 'APP自动化', 'int', 0,
     'APP 用例步骤生成开关（默认关；关=AI 生成链 stage2 维持 Web 词表零分流，开=APP/小程序目标按引擎描述符能力表裁剪可用动词生成 app.* IR 步骤，产出走既有评审工作台。AI 生成任务消费）'),
    ('ai.appauto.wx-mock', '1', 'APP自动化', 'int', 0,
     '小程序 mock 智辅开关（默认关；关=wx.request mock 数据集生成恒拒零外呼，开=LLM 按接口契约样例生成含边界值数据集，core 以 GenKit 校验和复校剪除假号。小程序环境档案/测试数据消费）')
ON CONFLICT (config_key) DO NOTHING;
