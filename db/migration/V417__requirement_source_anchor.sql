-- (BA batch, design section 3.2): evidence anchor column on tp_requirement.
-- Adopted extraction items keep documentId / chunkIds / verbatim evidence on the
-- requirement row so the generation chain can pull the original text block later
-- without re-parsing document previews. NULL means "no anchor" - manual points and
-- legacy call sites keep byte-for-byte the old behaviour.
ALTER TABLE tp_requirement ADD COLUMN IF NOT EXISTS source_anchor JSONB;

COMMENT ON COLUMN tp_requirement.source_anchor IS
    'Evidence anchor captured at adoption time: {"documentId":long,"chunkIds":[long],"evidence":"verbatim excerpt"}; NULL for unanchored requirements';
