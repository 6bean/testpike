-- V542__mcp_daily_quota_key.sql — daily call-quota governance key for the MCP endpoint.
-- Seeds mcp.quota.calls-per-token-day (factory default 2000 calls per API token per
-- calendar day). Consumed by McpDailyQuotaGuard on the MCP transport surface right
-- after authentication and the per-minute rate limit (both gates share one surface,
-- neither is bypassable by spreading calls thinly below the minute limit).
-- The value is hot-updatable through tp_sys_config; the guard reads it live on every
-- request, so changing the cap takes effect without a restart. Idempotent replay.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('mcp.quota.calls-per-token-day', '2000', '系统', 'int', 0, 'MCP 每个 Token 每日调用上限（自然日零点自动重置；超限后新的调用会被拒绝并提示当前次数，如需更高额度请调整本键）')
ON CONFLICT (config_key) DO NOTHING;
