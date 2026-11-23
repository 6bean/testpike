-- V375: AX batch — failure classification and visual locate join the
-- shared behaviour kernel (L0 clause assembly for the two one-shot judgement domains).
-- * governance keys ai.classify.directives.enabled / ai.vision.locate-directives.enabled:
--   factory default on (AI features ship enabled; only an explicit 0/false closes the
--   operator-tuned directive read; a missing row keeps directives on).
-- * seed rows: each domain's judgement contract, carried verbatim from the built-in
--   fallback head (value-range and output-schema requirements retained so the strict
--   parsers and downstream range guards stay intact), status=2 (full release).
--   Operators can rephrase the instruction wording on the Prompt Template page; the
--   failure-transparency/evidence-first invariant is appended by the kernel piece in
--   code and is not editable here.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.classify.directives.enabled', 'true', '系统', 'bool', 0,
     '失败归因行为指令开关（默认开启；开启时归因指令采用「提示词模板」页面中 classify-directives 的已发布内容，为保归因结果可采用，模板内容必须保留 1=产品缺陷 2=AI定位错误 3=环境问题 三分类值域与 JSON 输出格式要求；关闭或模板没有已发布内容时，按内置归因指令执行，归因不中断。修改即时生效，无需重启）'),
    ('ai.vision.locate-directives.enabled', 'true', '系统', 'bool', 0,
     '视觉定位行为指令开关（默认开启；开启时定位指令采用「提示词模板」页面中 locate-directives 的已发布内容，为保定位结果可采用，模板内容必须保留 x/y 像素坐标与 confidence 置信度的 JSON 输出格式要求；关闭或模板没有已发布内容时，按内置定位指令执行，定位应答不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('classify-directives', 'failure-classify', 1,
$$你是测试执行失败归因助手。根据执行器上报的失败详情，把失败归为以下三类之一：
1=产品缺陷（被测系统本身功能异常：接口 5xx、数据不对、页面行为与预期不符）；
2=AI定位错误（生成的脚本/定位器问题：元素定位失败、步骤缺失、断言写法错）；
3=环境问题（执行环境异常：服务不可达、超时、网络、测试数据或账号失效）。
只输出一个 JSON 对象，不要输出其他文字，格式：{"classification":数字,"note":"一句话中文说明"}$$,
 2, 0),
('locate-directives', 'visual-locate', 1,
$$你是移动端测试执行的视觉定位助手。给定失败步骤的目标元素描述、失败现场截图（截图见 image_url 段）与无障碍树候选清单，请在截图上找出该目标元素的点击中心点。
像素坐标以截图左上角为原点，x 向右、y 向下，单位为像素。
找不到目标时不要瞎猜：把 confidence 置为 0 并在 label 说明原因。
只输出一个 JSON 对象，不要输出其他文字，格式：{"x":数字,"y":数字,"label":"命中的元素名称或说明","confidence":0到1之间的小数}$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
