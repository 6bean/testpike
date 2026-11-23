-- V374: AX batch — spec interface-document extraction joins the
-- shared behaviour kernel (L0 clause assembly).
-- * governance key ai.spec.extract-directives.enabled: factory default on (AI features ship enabled;
--   only an explicit 0/false closes the operator-tuned directive read).
-- * seed row: the extraction contract, carried verbatim from the built-in
--   fallback head (marker and output schema retained so mock providers and
--   the parser contract stay intact), status=2 (full release). Operators can
--   rephrase the instruction wording on the Prompt Template page; the
--   failure-transparency/evidence-first invariant is appended by the kernel
--   piece in code and is not editable here.
-- Both inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.spec.extract-directives.enabled', 'true', '系统', 'bool', 0,
     '接口文档抽取行为指令开关（默认开启；开启时抽取指令采用「提示词模板」页面中 spec-extract-directives 的已发布内容，为保抽取结果可解析，模板内容必须保留 SPEC_DRAFT_EXTRACT_JSON 标记与 JSON 输出格式要求；关闭或模板没有已发布内容时，按内置抽取指令执行，抽取不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('spec-extract-directives', 'spec-extract', 1,
$$ 你是接口设计文档抽取器（SPEC_DRAFT_EXTRACT_JSON）。从给定文档分块中抽取接口定义条目，输出 JSON：
{"items":[{"method":"GET|POST|PUT|DELETE|PATCH","path":"/...",
"title":"接口名","description":"用途说明","tags":["分组名"],
"requestContentType":"application/json 等，可空",
"paramsSkeletonJson":{"查询/路径参数名":{"example":"示例值"}}，可空,
"bodySkeleton":{"请求体结构示例"}，可空,
"assertionsJson":[{"type":"status","expected":"200"}]，可空,
"securityRefs":["凭据键名"],可空,"authHintJson":可空,
"sourceAnchor":"该接口在原文中的连续原文片段（必填，逐字引用）"}]}
硬性约束：只抽取分块文本中真实存在的接口，绝不发明文档外接口；没有接口就输出 {"items":[]}；sourceAnchor 必须是原文逐字片段。 $$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
