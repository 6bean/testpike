-- Migration: hot-updatable in-flight window for document-extraction consumption .
--
-- Why this file exists:
--   When an extraction process died mid-run it left the in-flight idempotent
--   stamp behind with the annotation-wide 24h window; every redelivery inside
--   that window was acked as a duplicate and the document could hang "extracting"
--   for hours. The consume guard now takes over such orphan stamps immediately
--   once the crashed holder is proven dead, and this key sizes the backstop
--   window: the stamp itself expires after twice the consume lease (15min) so
--   redeliveries are never parked longer than ~30 minutes. Missing or invalid
--   values fall back to 1800 in the reader (the guard is never silently off).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.idempotent-inflight-seconds', '1800', '系统', 'int', 0, '文档提取在途任务恢复窗口秒数（默认 1800=消费租期 15 分钟的两倍；进程崩溃后同一提取任务最多在此窗口内自动允许重新执行，超时投递会被即时接管无需等待；热更即生效，非法值按 1800 回退）')
ON CONFLICT (config_key) DO NOTHING;
