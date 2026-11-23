-- Migration: streaming transport switch for the document-extraction LLM plane .
--
-- Why this file exists:
--   The three extraction-plane model calls (final extraction round, F5 per-group
--   summarize, gap re-extraction ask) ran blocking chat() only. A provider that
--   keeps producing tokens slower than the wall timeout but faster than the idle
--   bound was killed by the fixed per-attempt timeout, while the same call over
--   the streaming transport survives (the idle watchdog only fires between
--   events). This key routes those calls through the streaming entry of the
--   fallback chain; a stream that fails to establish explicitly retries once
--   non-streaming with a WARN naming this key. Missing/garbage reads fall back
--   to on in the reader (factory default = on; the extraction plane never goes
--   silently without a transport).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.extract-stream.enabled', 'true', '系统', 'bool', 0, '文档提取模型调用走流式通道开关（默认开启；提取遇供应商持续输出时不再被固定墙钟超时中断。流建立失败会自动改用非流式重试一次并在日志点名本键。改为 false 保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
