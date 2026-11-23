-- Migration: collection-level default auth and headers .
-- Why: every request inside a collection usually shares one authentication and
--      a few default headers (tenant id, content type). Before this column pair
--      users had to repeat the same auth/headers on every request row, which is
--      busywork and drifts silently when one row is edited and its twins are not.
-- Semantics: headers_json = row array [{name,value}] (same shape as the request
--      column, so the merge point reads both through one converter);
--      auth_json = auth form object (same vocabulary as the request column,
--      single source AuthFormContract). Both default to "absent" ('[]' / '{}'),
--      so every legacy collection keeps its exact old behavior with zero
--      backfill: an empty collection auth/headers never changes an envelope.
-- Merge lives at the dispatch single point (ApiTestEnvelopeAssembler):
--      headers merge per key case-insensitively (request wins on same name),
--      auth replaces as a whole block (request explicit {"type":"none"} opts out
--      and must never fall back to the collection default).

ALTER TABLE tp_apitest_collection
    ADD COLUMN IF NOT EXISTS headers_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS auth_json    JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN tp_apitest_collection.headers_json IS
    'Collection default headers, row array [{name,value}] (empty = nothing inherited). Merged case-insensitively under request-level keys at dispatch; request-level same-name wins.';

COMMENT ON COLUMN tp_apitest_collection.auth_json IS
    'Collection default auth form object (empty {} = nothing inherited; same vocabulary as tp_apitest_request.auth_json). A request without its own auth inherits this whole block; an explicit {"type":"none"} opts out.';
