-- V339__knowledge_doc_revision.sql — AO batch S4 ledger revision counter.
-- Purpose: every grounding citation must be able to name WHICH version of a
-- knowledge ledger row backed the answer. The ledger had no version shape at
-- all (only status/updated_at), so re-ingest of the same source silently
-- overwrote content and the dialog could not say "based on revision N".
-- Semantics: revision starts at 1 on first ingest; the upsert UPDATE branch
-- bumps it by 1 on every re-ingest. Existing rows default to 1 — history
-- before this migration cannot be reconstructed, the counter tracks every
-- ingestion from here on.
ALTER TABLE public.tp_knowledge_doc
    ADD COLUMN IF NOT EXISTS revision INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN public.tp_knowledge_doc.revision IS
    'Ledger revision: 1 at first ingest, +1 on every re-ingest update; cited by grounding answers as the audit anchor of which content version backed the reply';
