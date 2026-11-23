-- V548__mcp_doc_max_bytes_key.sql — byte-ceiling governance key for the MCP document channel.
-- Seeds mcp.doc.max-bytes (factory default 33554432 bytes = 32 MiB), the per-upload
-- ceiling enforced by RestMcpToolGateway.docUpload before any write hop: an oversized
-- upload is refused with the current size, the ceiling and this key's literal in the
-- message, so the operator knows both what happened and where to adjust it. The value
-- is hot-updatable through tp_sys_config; the gateway reads it live on every request
-- via RestCoreConfigClient, so changing the cap takes effect without a restart.
-- The shipped default stays in sync with DEFAULT_DOC_MAX_BYTES on the gateway class
-- (the fallback constant is the same number — drift between them is a defect).
-- Idempotent replay.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('mcp.doc.max-bytes', '33554432', '系统', 'int', 0, 'MCP 文档通道单次上传大小上限（字节，默认 33554432 即 32MB；超限的上传会被拒绝并提示当前大小与上限，如需更大额度请调整本键）')
ON CONFLICT (config_key) DO NOTHING;
