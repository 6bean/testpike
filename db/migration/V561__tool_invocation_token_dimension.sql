-- GA3 per-token observability: the MCP invocation ledger grows the presenting
-- token dimension so calls / failures / p95 latency can be aggregated per API
-- token from this detail table alone (no aggregate table is introduced).
-- Backward compatible: existing rows keep api_token_id = NULL (pre-GA3 window),
-- and ai/user callers that never present a token continue writing NULL.
-- tp_api_token is a logical reference without FK, same discipline as the
-- other unenforced references on this table (server_id / tool_id).

ALTER TABLE tp_tool_invocation
    ADD COLUMN IF NOT EXISTS api_token_id BIGINT;

COMMENT ON COLUMN tp_tool_invocation.api_token_id IS
    'Presenting API token id (tp_api_token logical reference, no FK); set for caller=mcp rows authenticated via an API token, NULL when the caller presented no token or the row predates this column';

-- Partial index for the per-token aggregation window scans.
CREATE INDEX IF NOT EXISTS idx_tool_invocation_token
    ON tp_tool_invocation (api_token_id, created_at)
    WHERE api_token_id IS NOT NULL;
