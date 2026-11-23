-- Migration: MCP knowledge sync subscriptions .
-- One row = one named subscription pulling an external MCP server's read-only
-- tool into the G1 knowledge ledger on a schedule. Registration guards (server
-- exists, tool present in the connection-test snapshot AND annotated read-only,
-- interval range, visible target project) live in the service layer (K8 style,
-- no DB CHECK beyond shape). Flyway is not enabled: apply manually to dev DB.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V341__mcp_knowledge_sync.sql

CREATE TABLE IF NOT EXISTS tp_mcp_knowledge_sync (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id             BIGINT        NOT NULL REFERENCES tp_project (id),
    server_id              BIGINT        NOT NULL REFERENCES tp_mcp_server (id),
    name                   VARCHAR(200)  NOT NULL,
    tool_name              VARCHAR(200)  NOT NULL,
    param_mapping          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    sync_interval_minutes  INT           NOT NULL DEFAULT 1440,
    target_project_id      BIGINT        NOT NULL DEFAULT 0,
    target_pack_id         BIGINT        NOT NULL DEFAULT 0,
    auto_activate          BOOLEAN       NOT NULL DEFAULT FALSE,
    enabled                BOOLEAN       NOT NULL DEFAULT TRUE,
    last_status            VARCHAR(16),
    last_error             VARCHAR(500),
    last_sync_at           TIMESTAMPTZ,
    last_new_count         INT,
    last_skipped_count     INT,
    consecutive_failures   INT           NOT NULL DEFAULT 0,
    created_by             BIGINT,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_mcp_knowledge_sync_project_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS ix_mcp_knowledge_sync_due
    ON tp_mcp_knowledge_sync (enabled, last_sync_at);

COMMENT ON TABLE tp_mcp_knowledge_sync IS 'MCP knowledge sync subscription ledger: scheduled read-only tool pulls into tp_knowledge_doc (AO-22 T454)';
COMMENT ON COLUMN tp_mcp_knowledge_sync.server_id IS 'Subscribed MCP server (tp_mcp_server.id); tool_name must be present and read-only in its connection-test snapshot';
COMMENT ON COLUMN tp_mcp_knowledge_sync.tool_name IS 'Read-only MCP tool name selected from the server snapshot (annotations.readOnlyHint=true, fail-closed otherwise)';
COMMENT ON COLUMN tp_mcp_knowledge_sync.param_mapping IS 'JSON object: tool input parameter -> fixed value or context key, applied on every scheduled call';
COMMENT ON COLUMN tp_mcp_knowledge_sync.target_project_id IS 'Knowledge landing bucket: 0 = global ledger bucket, >0 = owning project (visible-scope guard in service layer)';
COMMENT ON COLUMN tp_mcp_knowledge_sync.auto_activate IS 'Quality gate: false (factory default) = synced entries land as pending-activation for human batch activation; enabling is an explicit audited action';
COMMENT ON COLUMN tp_mcp_knowledge_sync.last_status IS 'Last round outcome: updated | unchanged | failed (written by the sync scheduler slice)';
COMMENT ON COLUMN tp_mcp_knowledge_sync.last_error IS 'Last failure reason, truncated to 500 chars (network/auth/protocol typing kept in the sync result, never crashes the loop)';
COMMENT ON COLUMN tp_mcp_knowledge_sync.consecutive_failures IS 'Consecutive failed rounds, reset to 0 on success; K2 alert threshold consumed from the hot-updatable key group (ai.mcp.sync.*)';
