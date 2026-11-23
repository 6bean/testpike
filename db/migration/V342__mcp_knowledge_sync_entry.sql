-- Migration: MCP knowledge sync lineage ledger .
-- One row = one landed payload version of one subscription. The fingerprint
-- (sha256 of the payload text) drives replay dedup; doc_id points at the
-- tp_knowledge_doc ledger row that carries the versioned body (knowledge docs
-- are versioned as source_ref mcp-sub-{subscriptionId}:v{n} because the
-- partial unique key uq_kb_doc_entity(source_type, project_id, source_ref)
-- would collide on a plain subscription-id ref; the title keeps the human
-- name plus the version marker). doc_id is deliberately NOT a FK: lineage is
-- a one-way snapshot that must survive ledger cleanup (retention sweeps).
-- Flyway is not enabled: apply manually to dev DB.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V342__mcp_knowledge_sync_entry.sql

CREATE TABLE IF NOT EXISTS tp_mcp_knowledge_sync_entry (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id BIGINT      NOT NULL REFERENCES tp_mcp_knowledge_sync (id) ON DELETE CASCADE,
    payload_sha     VARCHAR(64) NOT NULL,
    doc_id          BIGINT      NOT NULL,
    version_no      INT         NOT NULL,
    source_url      VARCHAR(500),
    state           VARCHAR(16) NOT NULL DEFAULT 'LANDED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mcp_sync_entry_version UNIQUE (subscription_id, version_no)
);

CREATE INDEX IF NOT EXISTS ix_mcp_sync_entry_lookup
    ON tp_mcp_knowledge_sync_entry (subscription_id, version_no DESC);

COMMENT ON TABLE tp_mcp_knowledge_sync_entry IS 'MCP knowledge sync lineage: one row per landed payload version (fingerprint dedup + source URL anchor)';
COMMENT ON COLUMN tp_mcp_knowledge_sync_entry.payload_sha IS 'sha256 hex of the landed payload text; equal to the latest row of the same subscription = replay = skipped (zero new rows)';
COMMENT ON COLUMN tp_mcp_knowledge_sync_entry.doc_id IS 'tp_knowledge_doc ledger row carrying this version (no FK: lineage snapshot outlives ledger retention)';
COMMENT ON COLUMN tp_mcp_knowledge_sync_entry.version_no IS 'Monotonic per-subscription revision counter (external content change lands a new version, previous kept for lineage)';
COMMENT ON COLUMN tp_mcp_knowledge_sync_entry.state IS 'LANDED = written into the knowledge ledger (pending activation or active per auto_activate); superseded marking rides the re-apply preview flow (package 4)';
