-- Reuse-scoped fidelity (ticket 1 follow-up of the D1 triage): a generation
-- that answers buckets from the proven cross-run bucket-result cache, or a
-- whole group from the per-generation item cache, covers that source scope
-- WITHOUT a fresh provider call. The new column books the covered scope so
-- the ledger can answer retention = (injected + reused) / source instead of
-- scoring a zero-call reuse leg against the whole document (the live false
-- red: generation 710db3de booked injected 6714 / source 13481 = 49.80 for
-- a document whose unchanged buckets were fully reused). Rows written
-- before this column keep the legacy face (0 = nothing reused = the old
-- arithmetic byte-identical); the whole-document floor semantics of fresh
-- full-document rounds never change.
ALTER TABLE tp_doc_extract_fidelity
    ADD COLUMN IF NOT EXISTS reused_source_tokens INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_doc_extract_fidelity.reused_source_tokens IS
    'Source tokens this generation covered by cache reuse instead of a fresh provider call (read-by-reuse face of the reuse-scoped retention).';
