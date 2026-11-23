-- V553__mcp_confirm_ttl_seconds_key.sql — validity window key for the MCP in-session confirmation loop.
-- Seeds mcp.confirm.ttl-seconds (factory default 1800), the one-shot lifetime of a
-- confirm_ref issued by an MCP write tool that hit the human-confirmation gate. The
-- reference is stored in Redis under mcp:confirm:{ref} with this TTL, so expiry is an
-- automatic end-of-life (no background sweep needed, R57 lifecycle bound at the
-- creation point). The value is hot-updatable through tp_sys_config and read live on
-- every issuance; illegal or non-positive values fall back to the shipped default.
-- The shipped default stays in sync with DEFAULT_TTL_SECONDS on McpConfirmGrantStore
-- (drift between them is a defect). Idempotent replay.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('mcp.confirm.ttl-seconds', '1800', '系统', 'int', 0, '站外智能体写操作确认码的有效期秒数（默认 1800，即 30 分钟；过期后确认码自动作废，重新发起写操作即可拿到新的确认码）')
ON CONFLICT (config_key) DO NOTHING;
