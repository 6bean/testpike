-- Migration: the exposure switch for the interface catalog on the inbound MCP face, and the
-- projection's refresh budget.
--
-- Why the switch ships CLOSED while the dialog's read door ships open:
--   The dialog door serves a logged-in member of this installation. This face serves whatever holds
--   an API token with scope mcp.read, from outside the installation — the endpoint names and the
--   parameter surface of internal doors become visible, and read results leave the product. That is
--   an outward-facing data surface, so it is opened by an operator deliberately, on the system
--   configuration page, rather than being on by default. It is the named exemption class already
--
-- The projection itself needs no list to maintain: the tool face is generated from the interface
-- catalog inside core, so a new endpoint appears for MCP callers in the same deployment that adds
-- it, subject to the same policy verdict (family exclusions, project anchor verification) that
-- governs the dialog.
--
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('mcp.api-access.enabled', '0', '系统', 'string', 0,
     '对外 MCP 面是否给出接口直通的只读工具集（默认关闭：该面按 API Token 对外提供，端点名称与参数面会离开本安装；打开后仅平台已判定可达的只读条目出现，变更类条目与敏感面条目不在此面。修改即时生效，最长延迟=工具集刷新周期 mcp.api-access.refresh-seconds）'),
    ('mcp.api-access.refresh-seconds', '300', '系统', 'int', 0,
     '对外工具集刷新接口目录的周期（秒，最小 1；刷新失败时沿用上一次工具集并记日志，不影响调用面——调用每次都过平台闸）')
ON CONFLICT (config_key) DO NOTHING;
