-- AG batch (M10 : flagship switches, honest hit provenance, LLM fallback cache shell.
--
-- Why this file exists:
--   1) The two M10 application points (A-3 llm-fallback / A-4 record-summarize)
--      previously had NO config keys because their job types were refused in
-- code. segment 1 unlocks the acceptance plane: both points get one
--      sub-switch each, default '0' (off) and ANDed with the master gate
--      ai.mock.enabled, exactly the V239 posture for the M9 points.
--   2) tp_mock_hit_log gains a `source` column: the honest-provenance chain
--      (X-TP-Mock-Source) must survive into the ledger so the inspector can
--      badge, and reports can count, "this response was AI-generated" rows.
--      NULL = row predates the provenance plane (absent vs stub must stay
--      tellable apart — never a fabricated default).
--   3) tp_mock_llm_cache table shell (A-3 repeat-answer cache): the LLM
--      fallback caches its generated response per space+normalized-request
--      fingerprint so the same question is answered instantly without
--      spending tokens again. It is deliberately its OWN table — AI output is
--      a generated artifact, mixing it into the recording cassette (real
--      traffic ledger) would break the honesty of both. MinIO reference for
--      oversized bodies, TTL managed by the sweep consumer (K8 keys).
--
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.mock.llm-fallback', '1', 'AI 配置', 'bool', 0,
     'A-3 LLM 兜底应答分开关：默认关；与总闸 ai.mock.enabled 做与运算，双开才允许未命中请求走模型兜底（生成物带 X-TP-Mock-Source: llm 硬标注，永不冒充真实集成）'),
    ('ai.mock.record-summarize', '1', 'AI 配置', 'bool', 0,
     'A-4 录制流量归纳分开关：默认关；与总闸做与运算，关态拒绝创建归纳任务；产物仅供人工勾选入库，永不直写规则库')
ON CONFLICT (config_key) DO NOTHING;

ALTER TABLE tp_mock_hit_log
    ADD COLUMN IF NOT EXISTS source VARCHAR(20);

COMMENT ON COLUMN tp_mock_hit_log.source IS
    'Honest provenance of the answer that served this row (stub | llm | proxy; NULL = row predates the provenance plane or plain miss) — inspector badges and report counts consume it';

CREATE TABLE IF NOT EXISTS tp_mock_llm_cache (
    id                BIGSERIAL    PRIMARY KEY,
    space_id          BIGINT       NOT NULL REFERENCES tp_mock_space (id),
    cache_key         VARCHAR(200) NOT NULL,
    method            VARCHAR(10)  NOT NULL,
    path              VARCHAR(500) NOT NULL,
    response_snapshot JSONB,
    artifact_ref      VARCHAR(300),
    tokens_spent      INT          NOT NULL DEFAULT 0,
    hit_count         INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    last_hit_at       TIMESTAMP,
    expires_at        TIMESTAMP,
    CONSTRAINT uq_mock_llm_cache_key UNIQUE (space_id, cache_key)
);
CREATE INDEX IF NOT EXISTS idx_mock_llm_cache_expiry ON tp_mock_llm_cache (expires_at);

COMMENT ON TABLE tp_mock_llm_cache IS 'A-3 LLM fallback answer cache (same masked-fingerprint question answered instantly without re-spending tokens; generated artifact, kept apart from the real-traffic cassette by design)';
COMMENT ON COLUMN tp_mock_llm_cache.cache_key IS 'space-scoped fingerprint: normalized method+path over the masked request digest (PII never enters the key)';
COMMENT ON COLUMN tp_mock_llm_cache.response_snapshot IS 'Masked generated response as jsonb {status,headers,body} for small bodies (NULL when the body lives in artifact_ref)';
COMMENT ON COLUMN tp_mock_llm_cache.artifact_ref IS 'MinIO reference for oversized generated bodies (cache rows stay metadata-light)';
COMMENT ON COLUMN tp_mock_llm_cache.tokens_spent IS 'Token cost paid when this answer was generated (cache hits spend zero — the whole point of the table)';
COMMENT ON COLUMN tp_mock_llm_cache.expires_at IS 'Freshness horizon from the K8 TTL key; the sweep reclaims expired rows, NULL = never expires by age';
