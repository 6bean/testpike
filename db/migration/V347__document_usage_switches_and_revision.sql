-- Per-document usage switches + optimistic-lock revision for tp_document.
-- The three booleans decide where a document's chunks may flow: requirement
-- extraction queue, case-generation prompt context, chat grounding retrieval.
-- Schema defaults are TRUE so every existing row keeps today's behaviour
-- (no backfill, no drift). REFERENCE uploads start with use_for_extraction
-- = FALSE at the application layer (category-derived default, not a schema
-- default: the rule only applies to newly uploaded reference documents).
-- revision is bumped by the row-level category/switch edit APIs and carried
-- in their requests as the optimistic-lock token.
ALTER TABLE tp_document
    ADD COLUMN IF NOT EXISTS use_for_extraction BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS use_in_case_prompt BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS use_in_grounding   BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS revision           INT     NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_document.use_for_extraction IS 'Whether this document may enter the requirement-extraction queue; REFERENCE uploads start switched off, the user can still switch it on';
COMMENT ON COLUMN tp_document.use_in_case_prompt IS 'Whether this document feeds the case-generation prompt context (spec-factor channel)';
COMMENT ON COLUMN tp_document.use_in_grounding   IS 'Whether this document feeds chat grounding retrieval';
COMMENT ON COLUMN tp_document.revision           IS 'Optimistic-lock counter bumped by document category/usage switch updates';
