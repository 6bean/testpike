-- AP-1 (3) document group-summary disk cache (ai.ctx.doc-summaries-cache, ships ON).
-- Re-extraction of the same document generation reads cached Map/Reduce group
-- summaries instead of re-burning them; a new extraction generation token (raised
-- whenever the document content is revised) invalidates the previous rows, so a
-- stale summary can never be fed into a fresh extraction.
CREATE TABLE IF NOT EXISTS tp_doc_summary_cache (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT      NOT NULL,
    gen_token    VARCHAR(64) NOT NULL,
    blocks       JSONB       NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_doc_summary_cache UNIQUE (document_id, gen_token)
);
CREATE INDEX IF NOT EXISTS idx_doc_summary_cache_doc ON tp_doc_summary_cache (document_id);

COMMENT ON TABLE tp_doc_summary_cache IS
    'Generation-keyed cache of Map/Reduce group summaries for document extraction (AP-1 side (3); switch ai.ctx.doc-summaries-cache, default on)';
COMMENT ON COLUMN tp_doc_summary_cache.document_id IS 'Source document (tp_document.id)';
COMMENT ON COLUMN tp_doc_summary_cache.gen_token IS 'Extraction generation token; a changed token invalidates older cache rows';
COMMENT ON COLUMN tp_doc_summary_cache.blocks IS 'Renumbered summary blocks [{seq,chapter,content}] consumed by the extraction stage';
