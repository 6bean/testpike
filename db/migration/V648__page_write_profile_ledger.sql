-- Migration: per-page network write-risk ledger.
-- The explore/survey browser guard observes which pages fire write-type
-- requests (POST/PUT/DELETE/PATCH) outside their load-time baseline: an
-- autosave page (input writes straight to the server) is a real mis-write
-- risk for probing, so the guard downgrades such pages to snapshot-only and
-- this ledger books the fact per page. It is deliberately NOT a second copy
-- of the element-collection tally (tp_site_survey_run.page_writes counts
-- candidate rows written per page; that column and this table carry
-- different facts and must not be conflated). Rows accumulate across runs
-- so the AI kernel prompt can annotate known write-surface pages before any
-- probe is planned. Page keys fold through WebElementPageKeys (the shared
-- ledger folding single source), so one page is one row everywhere.
-- Flyway is not enabled; this file is the replay source, applied manually to
-- the dev and chain-slot databases.
CREATE TABLE IF NOT EXISTS tp_page_write_profile (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT        NOT NULL REFERENCES tp_project (id),
    page_key        VARCHAR(500)  NOT NULL,
    page_url        VARCHAR(500)  NOT NULL,
    write_surface   JSONB         NOT NULL DEFAULT '[]'::jsonb,
    write_events    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    net_failures    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    snapshot_only   BOOLEAN       NOT NULL DEFAULT FALSE,
    last_run_id     BIGINT,
    first_seen_at   TIMESTAMP     NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uk_page_write_profile UNIQUE (project_id, page_key)
);

CREATE INDEX IF NOT EXISTS idx_page_write_profile_project
    ON tp_page_write_profile (project_id, last_seen_at DESC);

COMMENT ON TABLE tp_page_write_profile IS 'Per-page network write-risk ledger: pages observed firing baseline-outside write requests or HTTP failures during survey/probe; drives snapshot-only downgrade, kernel prompt annotation and read-only probe routing.';
COMMENT ON COLUMN tp_page_write_profile.page_key IS 'Folded page key (WebElementPageKeys single source) - same folding as the element ledger page keys, never a second convention.';
COMMENT ON COLUMN tp_page_write_profile.write_surface IS 'Write verbs seen in this page load window (autosave surface), latest frame wins; bounded by the ledger cap.';
COMMENT ON COLUMN tp_page_write_profile.write_events IS 'Baseline-outside write request rows attributed to probe actions (stepNo/method/url), latest frame window wins; bounded by the ledger cap.';
COMMENT ON COLUMN tp_page_write_profile.net_failures IS 'HTTP >=400 and requestfailed triples observed on this page (url/method/status, params redacted server-side at source); latest frame window wins; bounded by the ledger cap.';
COMMENT ON COLUMN tp_page_write_profile.snapshot_only IS 'Sticky conservative downgrade: once a write is observed this page stays read-only for later probes; never reset by a clean frame.';
