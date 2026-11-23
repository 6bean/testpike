-- V536: cross-run bucket-result reuse for the document extraction chain .
--
-- Why: every re-run of the same document re-burned ALL final-extract buckets
-- (the gold-sample windows paid ~2h of provider calls again even when most
-- buckets were byte-identical). This table caches the extracted item list per
-- bucket under a key that covers every input that can change the model answer
-- (bucket source blocks, model id, rendered prompts, dimension hint and the
-- split/budget parameter fingerprint), so an unchanged bucket is reused
-- VERBATIM with zero provider calls while any changed input misses naturally.
--
-- R57 resource pairing (declared at the creation point):
--   * row cap   ai.doc.bucket-cache-max-rows  -> LRU eviction after every write
--   * ttl       ai.doc.bucket-cache-ttl-days  -> expired rows read as misses and
--                                                swept on the next write
--   * terminal  failed/cancelled runs delete the rows they pre-occupied
--                                                (DocExtractServiceImpl terminal door)
--   * observability  hits/misses/rows counters logged per bucket plane
CREATE TABLE IF NOT EXISTS tp_doc_extract_bucket_cache (
    id           BIGSERIAL PRIMARY KEY,
    cache_key    CHAR(64)    NOT NULL,
    document_id  BIGINT      NOT NULL,
    gen_token    VARCHAR(64) NOT NULL,
    items        JSONB       NOT NULL,
    hit_count    INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_hit_at  TIMESTAMPTZ,
    CONSTRAINT uq_doc_extract_bucket_cache_key UNIQUE (cache_key)
);
CREATE INDEX IF NOT EXISTS idx_doc_extract_bucket_cache_run
    ON tp_doc_extract_bucket_cache (document_id, gen_token);
CREATE INDEX IF NOT EXISTS idx_doc_extract_bucket_cache_lru
    ON tp_doc_extract_bucket_cache ((COALESCE(last_hit_at, created_at)));

COMMENT ON TABLE tp_doc_extract_bucket_cache IS
    'Content-keyed reuse cache for final-extract bucket item lists; key = SHA-256 over bucket blocks x model x prompts x split/budget fingerprint (switch ai.doc.bucket-cache.enabled, default on)';
COMMENT ON COLUMN tp_doc_extract_bucket_cache.cache_key IS 'SHA-256 hex of every input that determines the bucket answer; any changed input yields a different key, so stale reuse is structurally impossible';
COMMENT ON COLUMN tp_doc_extract_bucket_cache.document_id IS 'Document whose run first produced this bucket result (provenance and terminal-delete anchor, not part of the key)';
COMMENT ON COLUMN tp_doc_extract_bucket_cache.gen_token IS 'Extraction generation that wrote the row; a failed or cancelled run deletes the rows it pre-occupied';
COMMENT ON COLUMN tp_doc_extract_bucket_cache.items IS 'Extracted requirement items exactly as the model pass returned them (byte-identical reuse face)';
COMMENT ON COLUMN tp_doc_extract_bucket_cache.hit_count IS 'Number of later runs that reused this row without a provider call (observability ledger)';
COMMENT ON COLUMN tp_doc_extract_bucket_cache.last_hit_at IS 'Most recent reuse; the LRU eviction orders by COALESCE(last_hit_at, created_at)';

-- Factory seeds (R32: AI features ship ON; descriptions carry operator-facing
-- operating info only: default, interval, consequence, where to change).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.doc.bucket-cache.enabled', 'true', '系统', 'bool', 0, '文档提取桶结果复用开关（默认开启；重跑同一文档时，内容与参数均未变化的提取桶直接复用已提取条目，不再调用模型。值缺失或不合规时按开启处理并在日志点名本键。关闭后重跑会重新调用模型；可在系统设置-AI参数分组改为 true，保存即生效，无需重启）'),
('ai.doc.bucket-cache-max-rows', '20000', '系统', 'int', 0, '文档提取桶结果缓存保留行数上限（达到上限时最久未命中的行会被逐出，不影响正在重跑的文档；允许范围 1-200000，不合规值按 20000 处理并在日志点名本键，保存即生效）'),
('ai.doc.bucket-cache-ttl-days', '30', '系统', 'int', 0, '文档提取桶结果缓存保留天数（超过天数的行按未命中处理并在下次写入时清理，等同重新调用模型；允许范围 1-365，不合规值按 30 处理并在日志点名本键，保存即生效）')
ON CONFLICT (config_key) DO NOTHING;
