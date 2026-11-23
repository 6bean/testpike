-- Migration V358: shared asset publish chain .
-- tp_asset is a single-table version chain modelled after tp_api_spec (V109) — reusing
-- the established chain shape instead of a second version mechanism. Publishing the
-- same (project_id, kind, name) again appends a row with asset_version+1 and links
-- prev_asset_id to the previous row. Owner = publishing project (project_id NOT NULL);
-- published heads are globally discoverable, every write (re-publish / withdraw) is
-- fenced to the owner project server-side (SharedAssetVisibilityGuard single source).
-- status: 1 = published, 2 = withdrawn at chain level; superseded version rows stay
-- readable because references pin concrete row ids (tp_asset_reference, .
-- payload_json is a content snapshot of the publisher's entity, never a live pointer;
-- credential material is stored as {{CRED:key}} names only (V257 discipline).
-- Flyway not enabled: apply manually to dev db.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V358__shared_asset_publish_chain.sql

CREATE TABLE IF NOT EXISTS tp_asset (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT        NOT NULL,
    kind          VARCHAR(24)   NOT NULL,
    name          VARCHAR(200)  NOT NULL,
    asset_version INT           NOT NULL DEFAULT 1,
    prev_asset_id BIGINT,
    status        SMALLINT      NOT NULL DEFAULT 1,
    payload_json  TEXT          NOT NULL,
    created_by    BIGINT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_asset_kind CHECK (kind IN
        ('case_fragment', 'action_component', 'login_recipe', 'gen_function')),
    CONSTRAINT ck_asset_status CHECK (status IN (1, 2)),
    CONSTRAINT uq_asset_project_kind_name_version UNIQUE (project_id, kind, name, asset_version)
);
CREATE INDEX IF NOT EXISTS idx_asset_chain_head
    ON tp_asset (project_id, kind, name, asset_version DESC);

COMMENT ON TABLE  tp_asset               IS 'Shared asset publish chain: per-project version-linked content snapshots (T288 slice 1)';
COMMENT ON COLUMN tp_asset.project_id    IS 'Owner = publishing project; cross-project readers gain discoverability only, never write access';
COMMENT ON COLUMN tp_asset.kind          IS 'Asset kind: case_fragment | action_component | login_recipe | gen_function';
COMMENT ON COLUMN tp_asset.prev_asset_id IS 'Previous version row id (chain link); NULL for the first publish';
COMMENT ON COLUMN tp_asset.status        IS '1 = published (head globally discoverable), 2 = withdrawn at chain level';
COMMENT ON COLUMN tp_asset.payload_json  IS 'Per-kind content snapshot (case step array / action JSON fragment / login recipe template / data factory content); credential values stay out, only {{CRED:key}} names';
