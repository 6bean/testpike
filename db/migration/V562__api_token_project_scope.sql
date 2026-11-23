-- GA1 token project scope: an API token can be pinned to an explicit set of
-- project ids so the MCP gateway can reject calls targeting projects outside
-- the pin. Backward compatible by design: NULL or empty means "no narrowing",
-- and the gateway keeps the existing owner-membership check as the only gate
-- (every pre-GA1 token behaves exactly as before this column existed).
ALTER TABLE tp_api_token
    ADD COLUMN IF NOT EXISTS project_scope VARCHAR(200);

COMMENT ON COLUMN tp_api_token.project_scope IS
    'Comma-separated project ids the token is pinned to (normalized: numeric ids, de-duplicated, ascending); NULL or empty = unrestricted, owner-membership check applies as before';
