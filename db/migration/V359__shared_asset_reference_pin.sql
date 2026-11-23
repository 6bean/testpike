-- Migration V359: shared asset reference pins .
-- tp_asset_reference pins one host entity (referrer_kind + referrer_id) to exactly one
-- concrete tp_asset version row. Re-publishing upstream never moves the pin: consumers
-- see the staleness marker and explicitly re-pin (upsert keeps a single row per host),
-- so both directions of the bidirectional query stay single-SQL and N+1-free:
--   "who uses me"  = rows joined on the asset chain (project_id, kind, name)
--   "what I use"   = rows filtered by referrer_project_id
-- referrer_id is a logical locator resolved by the consuming domain (case step, journey
-- step, plan host, ...); no FK is declared because the host table varies by kind.
-- Pinned version rows must stay readable after chain withdrawal, hence no cascade rules.
-- Flyway not enabled: apply manually to dev db.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V359__shared_asset_reference_pin.sql

CREATE TABLE IF NOT EXISTS tp_asset_reference (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_row_id        BIGINT   NOT NULL REFERENCES tp_asset (id),
    referrer_project_id BIGINT   NOT NULL REFERENCES tp_project (id),
    referrer_kind       VARCHAR(32) NOT NULL,
    referrer_id         BIGINT   NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_asset_reference_host UNIQUE (referrer_kind, referrer_id)
);
CREATE INDEX IF NOT EXISTS idx_asset_reference_row
    ON tp_asset_reference (asset_row_id);
CREATE INDEX IF NOT EXISTS idx_asset_reference_project
    ON tp_asset_reference (referrer_project_id);

COMMENT ON TABLE  tp_asset_reference                   IS 'Shared asset pool reference pins: host entity -> concrete tp_asset version row (T288 slice 2)';
COMMENT ON COLUMN tp_asset_reference.asset_row_id      IS 'Pinned tp_asset version row id; never auto-follows a re-publish (staleness is surfaced, re-pin is explicit)';
COMMENT ON COLUMN tp_asset_reference.referrer_project_id IS 'Consumer project that owns the host entity; write calls are fenced to the acting project server-side';
COMMENT ON COLUMN tp_asset_reference.referrer_kind     IS 'Host entity type literal (e.g. case_step); resolved by the consuming domain, no cross-table FK';
COMMENT ON COLUMN tp_asset_reference.referrer_id       IS 'Host entity row id inside the consumer project; unique per (referrer_kind, referrer_id) so a host holds exactly one pin';
