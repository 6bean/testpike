-- V549__mcp_gen_max_requirements_key.sql — count-ceiling governance key for the MCP generation trigger.
-- Seeds mcp.gen.max-requirements (factory default 200), the per-call ceiling over
-- requirement ids enforced by RestMcpToolGateway.genCasesFromRequirement before any
-- queue hop: an oversized selection is refused with the current count, the ceiling
-- and this key's literal in the message, so the caller knows both what happened and
-- where to adjust it. The value is hot-updatable through tp_sys_config; the gateway
-- reads it live on every request via RestCoreConfigClient, so changing the cap takes
-- effect without a restart. The shipped default stays in sync with
-- DEFAULT_GEN_MAX_REQUIREMENTS on the gateway class (drift between them is a defect).
-- Idempotent replay.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('mcp.gen.max-requirements', '200', '系统', 'int', 0, 'MCP 生成触发单次需求点数量上限（默认 200；超限的触发会被拒绝并提示当前数量与上限，如需更大额度请调整本键）')
ON CONFLICT (config_key) DO NOTHING;
