-- Align operator-facing descriptions with the shipped ON defaults for AI
-- switches whose seed values were flipped by the AI default-on batch but
-- whose wording still advertised a factory-closed state. Idempotent replay
-- via IS DISTINCT FROM (guards on the NEW text); display text only.

UPDATE tp_sys_config SET description = 'AI 环境诊断开关（默认开；关=引擎体检只出确定性差集零 LLM，开=缺件项追加 AI 修复建议步骤含安装命令。引擎中心→体检按钮消费）'
 WHERE config_key = 'ai.appauto.env-doctor'
   AND description IS DISTINCT FROM 'AI 环境诊断开关（默认开；关=引擎体检只出确定性差集零 LLM，开=缺件项追加 AI 修复建议步骤含安装命令。引擎中心→体检按钮消费）';

UPDATE tp_sys_config SET description = '视觉模型总闸（默认开；关=视觉类 AI 应用点全部零调用零外呼原样报错，开=需分开关逐点点亮。上位闸与 appauto-locate 等分开关与运算）'
 WHERE config_key = 'ai.vision.enabled'
   AND description IS DISTINCT FROM '视觉模型总闸（默认开；关=视觉类 AI 应用点全部零调用零外呼原样报错，开=需分开关逐点点亮。上位闸与 appauto-locate 等分开关与运算）';

UPDATE tp_sys_config SET description = 'APP 步视觉定位兜底分开关（默认开；开=APP 自动化步确定性定位失败后经截图+视图树调视觉模型做三源共识定位，跑测详情时间线出「AI 定位」绿角标与定位证据包。引擎中心→跑测详情消费）'
 WHERE config_key = 'ai.vision.appauto-locate'
   AND description IS DISTINCT FROM 'APP 步视觉定位兜底分开关（默认开；开=APP 自动化步确定性定位失败后经截图+视图树调视觉模型做三源共识定位，跑测详情时间线出「AI 定位」绿角标与定位证据包。引擎中心→跑测详情消费）';

UPDATE tp_sys_config SET description = 'APP flaky 哨兵 AI 建议翻译开关（默认开；关=用例×设备稳定性分纯统计判定零模型外呼，开=翻转命中的组追加修复建议（换等待策略/换定位通道）。引擎中心→用例稳定性看板消费）'
 WHERE config_key = 'ai.appauto.flaky-sentinel'
   AND description IS DISTINCT FROM 'APP flaky 哨兵 AI 建议翻译开关（默认开；关=用例×设备稳定性分纯统计判定零模型外呼，开=翻转命中的组追加修复建议（换等待策略/换定位通道）。引擎中心→用例稳定性看板消费）';

UPDATE tp_sys_config SET description = 'APP 步骤自愈采纳闸开关（默认开；关=建议单人批采纳恒拒零写回，开=人批后候选定位合入脚本真实落版+回灌样本库。评审台→自愈建议单消费）'
 WHERE config_key = 'ai.appauto.step-healing'
   AND description IS DISTINCT FROM 'APP 步骤自愈采纳闸开关（默认开；关=建议单人批采纳恒拒零写回，开=人批后候选定位合入脚本真实落版+回灌样本库。评审台→自愈建议单消费）';

UPDATE tp_sys_config SET description = 'APP 自然语言步骤生成开关（默认开；关=一句中文转步骤恒拒零外呼，开=描述经目标引擎能力位图裁剪生成 app.* IR 步骤草稿，用户确认后并入步骤序列。用例编辑区 App 步骤消费）'
 WHERE config_key = 'ai.appauto.nl-step'
   AND description IS DISTINCT FROM 'APP 自然语言步骤生成开关（默认开；关=一句中文转步骤恒拒零外呼，开=描述经目标引擎能力位图裁剪生成 app.* IR 步骤草稿，用户确认后并入步骤序列。用例编辑区 App 步骤消费）';

UPDATE tp_sys_config SET description = 'APP 录制转流程开关（默认开；关=录制草稿登记与勾选入库恒拒零外呼零写库，开=录制事件经反推生成 app.* IR 步骤草稿进两层质量门，人批勾选才入库。用例编辑区 App 步骤录制入口消费）'
 WHERE config_key = 'ai.appauto.record-to-flow'
   AND description IS DISTINCT FROM 'APP 录制转流程开关（默认开；关=录制草稿登记与勾选入库恒拒零外呼零写库，开=录制事件经反推生成 app.* IR 步骤草稿进两层质量门，人批勾选才入库。用例编辑区 App 步骤录制入口消费）';

UPDATE tp_sys_config SET description = 'APP 用例步骤生成开关（默认开；关=AI 生成链 stage2 维持 Web 词表零分流，开=APP/小程序目标按引擎描述符能力表裁剪可用动词生成 app.* IR 步骤，产出走既有评审工作台。AI 生成任务消费）'
 WHERE config_key = 'ai.appauto.gen-steps'
   AND description IS DISTINCT FROM 'APP 用例步骤生成开关（默认开；关=AI 生成链 stage2 维持 Web 词表零分流，开=APP/小程序目标按引擎描述符能力表裁剪可用动词生成 app.* IR 步骤，产出走既有评审工作台。AI 生成任务消费）';

UPDATE tp_sys_config SET description = '小程序 mock 智辅开关（默认开；关=wx.request mock 数据集生成恒拒零外呼，开=LLM 按接口契约样例生成含边界值数据集，core 以 GenKit 校验和复校剪除假号。小程序环境档案/测试数据消费）'
 WHERE config_key = 'ai.appauto.wx-mock'
   AND description IS DISTINCT FROM '小程序 mock 智辅开关（默认开；关=wx.request mock 数据集生成恒拒零外呼，开=LLM 按接口契约样例生成含边界值数据集，core 以 GenKit 校验和复校剪除假号。小程序环境档案/测试数据消费）';

UPDATE tp_sys_config SET description = 'U-adv 升级顾问开关（默认开；关=顾问口 403 拒绝并说明原因零模型外呼，开=manifest 破坏性变更×用例特征画像出升级风险清单，消费位随支持度批 AI 包接线）'
 WHERE config_key = 'ai.appauto.upgrade-advisor'
   AND description IS DISTINCT FROM 'U-adv 升级顾问开关（默认开；关=顾问口 403 拒绝并说明原因零模型外呼，开=manifest 破坏性变更×用例特征画像出升级风险清单，消费位随支持度批 AI 包接线）';

UPDATE tp_sys_config SET description = 'U-auto 智能排窗开关（默认开；关=排窗口 403 拒绝并说明原因零模型外呼，开=计划/批次忙闲→推荐窗口+灰度切法，只推荐不落权，与在跑计划冲突红线拒答）'
 WHERE config_key = 'ai.appauto.upgrade-window'
   AND description IS DISTINCT FROM 'U-auto 智能排窗开关（默认开；关=排窗口 403 拒绝并说明原因零模型外呼，开=计划/批次忙闲→推荐窗口+灰度切法，只推荐不落权，与在跑计划冲突红线拒答）';

UPDATE tp_sys_config SET description = '矩阵 AI 智能抽样开关（默认开；关=推荐口 403 拒绝并说明原因零模型外呼，开=覆盖率/成本画像推荐 Top-N 组合只建议人确认）'
 WHERE config_key = 'ai.appauto.matrix-sample'
   AND description IS DISTINCT FROM '矩阵 AI 智能抽样开关（默认开；关=推荐口 403 拒绝并说明原因零模型外呼，开=覆盖率/成本画像推荐 Top-N 组合只建议人确认）';

UPDATE tp_sys_config SET description = '弹窗规则自学开关（默认开；关=从失败录屏帧学习弹窗模式的入口恒 403 拒绝并说明原因零模型外呼，开=监看帧剔除敏感帧后识别新弹窗模式→规则草稿进人批准门，批准才入库。失败任务监看帧消费）'
 WHERE config_key = 'ai.appauto.popup-rule-learn'
   AND description IS DISTINCT FROM '弹窗规则自学开关（默认开；关=从失败录屏帧学习弹窗模式的入口恒 403 拒绝并说明原因零模型外呼，开=监看帧剔除敏感帧后识别新弹窗模式→规则草稿进人批准门，批准才入库。失败任务监看帧消费）';

UPDATE tp_sys_config SET description = 'App 探索测试总开关（默认开；关=零外呼零残留，开启后允许移动形态探索会话创建与派发）'
 WHERE config_key = 'ai.explore.appauto'
   AND description IS DISTINCT FROM 'App 探索测试总开关（默认开；关=零外呼零残留，开启后允许移动形态探索会话创建与派发）';

UPDATE tp_sys_config SET description = 'Mock 模块 AI 总闸：默认开；与各应用点分开关做与运算，任一关则该应用点不生效'
 WHERE config_key = 'ai.mock.enabled'
   AND description IS DISTINCT FROM 'Mock 模块 AI 总闸：默认开；与各应用点分开关做与运算，任一关则该应用点不生效';

UPDATE tp_sys_config SET description = 'A-1 自然语言建 Mock 分开关：默认开；与总闸 ai.mock.enabled 做与运算，任一关则拒绝创建草稿任务（报错说明原因+审计留痕）'
 WHERE config_key = 'ai.mock.nl-stub'
   AND description IS DISTINCT FROM 'A-1 自然语言建 Mock 分开关：默认开；与总闸 ai.mock.enabled 做与运算，任一关则拒绝创建草稿任务（报错说明原因+审计留痕）';

UPDATE tp_sys_config SET description = 'A-2 契约→边缘场景包分开关：默认开；与总闸做与运算，关态拒绝创建故障场景集任务'
 WHERE config_key = 'ai.mock.edge-scenarios'
   AND description IS DISTINCT FROM 'A-2 契约→边缘场景包分开关：默认开；与总闸做与运算，关态拒绝创建故障场景集任务';

UPDATE tp_sys_config SET description = 'A-5 契约变更→Mock 修补建议分开关：默认开；确定性挂标不受本闸影响，仅 AI 建议任务受控'
 WHERE config_key = 'ai.mock.spec-sync'
   AND description IS DISTINCT FROM 'A-5 契约变更→Mock 修补建议分开关：默认开；确定性挂标不受本闸影响，仅 AI 建议任务受控';

UPDATE tp_sys_config SET description = 'A-3 LLM 兜底应答分开关：默认开；与总闸 ai.mock.enabled 做与运算，双开才允许未命中请求走模型兜底（生成物带 X-TP-Mock-Source: llm 硬标注，永不冒充真实集成）'
 WHERE config_key = 'ai.mock.llm-fallback'
   AND description IS DISTINCT FROM 'A-3 LLM 兜底应答分开关：默认开；与总闸 ai.mock.enabled 做与运算，双开才允许未命中请求走模型兜底（生成物带 X-TP-Mock-Source: llm 硬标注，永不冒充真实集成）';

UPDATE tp_sys_config SET description = 'A-4 录制流量归纳分开关：默认开；与总闸做与运算，关态拒绝创建归纳任务；产物仅供人工勾选入库，永不直写规则库'
 WHERE config_key = 'ai.mock.record-summarize'
   AND description IS DISTINCT FROM 'A-4 录制流量归纳分开关：默认开；与总闸做与运算，关态拒绝创建归纳任务；产物仅供人工勾选入库，永不直写规则库';

UPDATE tp_sys_config SET description = '压测 AI 草拟场景分开关：默认开；开启后一句话可生成向导预填草稿，草稿须经人工确认才会保存，调用模型会产生费用'
 WHERE config_key = 'ai.perf.scenario-draft'
   AND description IS DISTINCT FROM '压测 AI 草拟场景分开关：默认开；开启后一句话可生成向导预填草稿，草稿须经人工确认才会保存，调用模型会产生费用';

UPDATE tp_sys_config SET description = '压测 AI 归因解读分开关：默认开；开启后已完成的压测报告可请求模型生成通俗解读，调用模型会产生费用'
 WHERE config_key = 'ai.perf.insight'
   AND description IS DISTINCT FROM '压测 AI 归因解读分开关：默认开；开启后已完成的压测报告可请求模型生成通俗解读，调用模型会产生费用';
