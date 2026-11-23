-- V349__mcp_outbound_desensitize.sql — (AO-23) MCP outbound desensitization gate.
-- Adds the honest audit facet "masked" to tp_tool_invocation: 1 = the outbound
-- body was scrubbed through the shared DesensitizeUtils masks before leaving
-- the platform, 0 = the gate was explicitly turned off and the body left
-- untouched (recorded honestly, never silently), NULL = no outbound body at
-- all (e.g. handshake-failure traces). Seeds the hot-updatable switch key
-- ai.mcp.outbound-desensitize-enforce (factory default = on; an explicit
-- "false" is the enterprise downgrade, each row still carries its own mark).
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

ALTER TABLE tp_tool_invocation
    ADD COLUMN IF NOT EXISTS masked SMALLINT;

ALTER TABLE tp_tool_invocation
    DROP CONSTRAINT IF EXISTS ck_tool_invocation_masked;
ALTER TABLE tp_tool_invocation
    ADD CONSTRAINT ck_tool_invocation_masked CHECK (masked IS NULL OR masked IN (0, 1));

COMMENT ON COLUMN tp_tool_invocation.masked IS
    'Outbound desensitization facet (T455): 1=body masked before sending, 0=gate off pass-through, NULL=no outbound body';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.mcp.outbound-desensitize-enforce', 'true', '系统', 'bool', 0,
 '向外部 MCP 服务发起调用前是否先对请求体脱敏（手机号/邮箱/凭据打码；默认开启，在本页改回 false 即时生效无需重启；关闭后请求体原样出站，调用留痕按行如实记录未脱敏）')
ON CONFLICT (config_key) DO NOTHING;
