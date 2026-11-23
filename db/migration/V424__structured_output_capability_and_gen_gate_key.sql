-- structured output for generation stages (verdict §three.4):
-- tp_llm_config.structured_output marks whether an endpoint accepts an
-- OpenAI-compatible response_format block. Factory default ON (AI features
-- ship enabled, zero configuration); a provider that still refuses the block
-- is degraded to the legacy parse-and-revise path at the consumption point
-- with a visible warn line, so a mis-marked row can never break generation.
-- 0 = opt-out row for a known-unsupported model (auditable).
--
-- K8 switch ai.gen.structured-output.enabled (hot update, no restart): ships
-- ON; when off every request keeps the legacy shape byte-identical. The freed
-- format-retry budget is redirected to the faithfulness checker in the next
-- stage of this batch.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

ALTER TABLE tp_llm_config ADD COLUMN IF NOT EXISTS structured_output SMALLINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN tp_llm_config.structured_output IS
    'Structured-output capability for this model (1 = accepts a response_format block, factory default; 0 = opt out). Controls request-side output constraints only; never bypasses the write-confirmation gate.';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.structured-output.enabled', '1', '系统', 'int', 0, '用例与脚本生成的结构化输出开关：开启时由模型端直接按 JSON 结构产出结果，格式不合规则次数趋零（默认开；端点不支持时自动回退旧解析路径；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
