-- : a replay-stable content fingerprint on every requirement.
-- The fingerprint is defined exactly once, here, as an IMMUTABLE SQL function;
-- the table column below is a GENERATED ALWAYS ... STORED projection of it, so
-- no application code path can ever write a second, drifting implementation
-- (single-source rule R40). Display format XXXX-XXXX = first 8 hex chars of
-- sha256 over lower-cased, whitespace-folded "doc attribution + body", where
-- the attribution prefers the anchored documentId and falls back to source_id,
-- so an extracted item shows the same fingerprint on the preview list before
-- adoption and on the management list after it.
--
-- NOTE: ADD COLUMN ... STORED rewrites tp_requirement once (dev-scale table,
-- acceptable); after this point the column always has exactly one writer —
-- the database itself.

CREATE OR REPLACE FUNCTION requirement_fingerprint(doc_key TEXT, body TEXT)
RETURNS TEXT
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT substr(upper(encode(sha256(convert_to(
                trim(regexp_replace(
                    lower(coalesce(doc_key, '')) || chr(10) || lower(coalesce(body, '')),
                    '\s+', ' ', 'g')),
                'UTF8')), 'hex')), 1, 4)
           || '-'
           || substr(upper(encode(sha256(convert_to(
                trim(regexp_replace(
                    lower(coalesce(doc_key, '')) || chr(10) || lower(coalesce(body, '')),
                    '\s+', ' ', 'g')),
                'UTF8')), 'hex')), 5, 4);
$$;

COMMENT ON FUNCTION requirement_fingerprint(TEXT, TEXT) IS
    'Stable short fingerprint (XXXX-XXXX) of doc attribution + normalized text; case/whitespace insensitive, immutable, single source for the tp_requirement.fingerprint generated column and for preview-side computation of the same value';

ALTER TABLE tp_requirement ADD COLUMN IF NOT EXISTS fingerprint TEXT
    GENERATED ALWAYS AS (requirement_fingerprint(
        coalesce(source_anchor ->> 'documentId', source_id, ''),
        title || chr(10) || coalesce(acceptance_criteria, ''))) STORED;

COMMENT ON COLUMN tp_requirement.fingerprint IS
    'Replay-stable content fingerprint (generated, never app-written): doc attribution (anchored documentId first, else source_id) + normalized title/acceptance-criteria hashed to XXXX-XXXX; doubles as dedup reference and cross-page anchor (T695)';
