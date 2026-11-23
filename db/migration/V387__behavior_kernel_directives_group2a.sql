-- V387: AX batch per-domain upgrade group 2 (first half) — the matrix
-- sampling and evaluation question-generation legs join the shared behaviour
-- kernel (L0 head assembly via BehaviorKernelAssembly in code).
-- * governance keys ai.appauto.matrix-sample-directives.enabled /
--   ai.judge.qa-gen-directives.enabled:
--   factory default on (AI features ship enabled; only an explicit 0/false
--   closes the operator-tuned directive read; a missing row keeps directives on).
-- * seed rows: each leg's generation contract, carried verbatim from the
--   built-in fallback head (candidate-list verbatim rule, JUDGE_QA_GEN_JSON
--   marker and output-JSON requirements retained so the strict parsers and
--   core-side re-checks stay intact), status=2 (full release). Operators can
--   rephrase the instruction wording on the Prompt Template page; the
--   failure-transparency and evidence-first invariant is appended by the
--   kernel piece in code and is not editable here.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.appauto.matrix-sample-directives.enabled', 'true', '系统', 'bool', 0,
     '矩阵智能抽样行为指令开关（默认开启；开启时抽样指令采用「提示词模板」页面中 matrix-sample-directives 的已发布内容，为保推荐条目可采用，模板内容必须保留「逐字来自候选清单」约束与 recommendations JSON 输出格式要求；关闭或模板没有已发布内容时，按内置抽样指令执行，抽样推荐不中断。修改即时生效，无需重启）'),
    ('ai.judge.qa-gen-directives.enabled', 'true', '系统', 'bool', 0,
     '评测集 AI 造题行为指令开关（默认开启；开启时造题指令采用「提示词模板」页面中 qa-gen-directives 的已发布内容，为保出题结果可解析与出处复核，模板内容必须保留 JUDGE_QA_GEN_JSON 标记与 items JSON 输出格式要求；关闭或模板没有已发布内容时，按内置造题指令执行，AI 造题不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('matrix-sample-directives', 'appauto-matrix-sample', 1,
$$你是移动端自动化测试平台的机型矩阵抽样顾问。兼容性测试的「引擎×平台×机型」组合会爆炸，平台已程序化实算出全部候选组合面与画像数字（不是估计值），你的任务=从中挑出最值得先跑的前 N 个组合：优先覆盖不同机型轴与高通过率引擎、可寻址设备的组合优先（addressable=false 的仅代表当前项目没有该类设备档案，若机型覆盖缺口需要它也可以推荐并在理由里说明）、成本上控制总机时。
只输出一个 JSON 对象，格式：{"recommendations":[{"engineCode":"...","platformCode":"...","deviceKindCode":"..."}],"summary":"一句话推荐理由"}
recommendations 数组每项的三个字段值必须逐字来自候选清单（禁止自造引擎/平台/设备类，禁止输出 JSON 以外的任何文字），条数不超过要求且按推荐优先级排序。
禁止编造画像里没有的数字。$$,
 2, 0),
('qa-gen-directives', 'judge-qa-gen', 1,
$$你是评测集造题器（JUDGE_QA_GEN_JSON）。从给定文档分块中生成带参考答案的问答对，输出 JSON：
{"items":[{"question":"问题","answer":"参考答案",
"sourceAnchor":"答案在原文中的连续原文片段（必填，逐字引用）"}]}
硬性约束：只出文档内可回答的 QA 对，答案带出处原句 sourceAnchor，禁发明文档外事实；该分块出不了题就输出 {"items":[]}。$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
