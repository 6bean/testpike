-- Migration: apitest flow templates .
-- Why: a curated collection flow (steps + orchestration rows) must be reusable
--      across projects ("save as template" -> "instantiate into a collection").
--      The template stores a frozen snapshot in the same pinned shape the
--      collection-run ledger uses, so "what you saved" is exactly "what runs".
-- Semantics: steps_snapshot_json is an array of request snapshots (tree order);
--      source_collection_id is provenance only (ON DELETE SET NULL keeps the
--      frozen snapshot alive after the source collection is deleted — the
--      template never re-reads live request rows).

CREATE TABLE IF NOT EXISTS tp_apitest_flow_template (
    id                    bigserial PRIMARY KEY,
    project_id            bigint       NOT NULL REFERENCES tp_project (id),
    name                  varchar(200) NOT NULL,
    description           varchar(1000) NOT NULL DEFAULT '',
    source_collection_id  bigint       REFERENCES tp_apitest_collection (id) ON DELETE SET NULL,
    steps_snapshot_json   jsonb        NOT NULL DEFAULT '[]'::jsonb,
    created_by            bigint,
    created_at            timestamp    NOT NULL DEFAULT now(),
    updated_at            timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_apitest_flow_template_project
    ON tp_apitest_flow_template (project_id, id);

COMMENT ON TABLE tp_apitest_flow_template IS
    'Cross-project reusable flow snapshot: frozen request definitions (pinned shape), provenance FK never blocks deletion';
COMMENT ON COLUMN tp_apitest_flow_template.steps_snapshot_json IS
    'Array of request snapshots in collection order (same keys as tp_apitest_run_set.pinned_json: requestId/name/method/urlPath/headersJson/bodyText/authJson/assertionsJson + optional preconditionJson/loopJson/protocol)';
COMMENT ON COLUMN tp_apitest_flow_template.source_collection_id IS
    'Collection the template was saved from (traceability only; NULL after that collection is deleted — the snapshot stands on its own)';
