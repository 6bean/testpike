-- Migration: contract change-analysis queue (W1c F3-twin for API specs).
-- Applied manually to the dev database (Flyway not enabled; db/migration is reference).
-- Semantics:
--   tp_api_spec_change_analysis: one row per (old_spec_id, new_spec_id) pair produced
--     when an upload/fetch lands a non-empty endpoint diff against the previous version.
--     changes JSONB = [{endpointKey,changeType:add|changed|removed,requestIds[],
--     caseIds[],mockStubIds[]}] — impact lists are closed by the folded contract key
--     (tp_apitest_request.contract_endpoint_key equality, indexed; openapi-sourced
--     mock stubs via DerivedMockKeyLookup). Changes that match nothing are still
--     listed with empty impact lists — the queue never pretends a closure it did not
--     find. Only keys are stored (no snapshot text) so a later contract restyle
--     cannot rot the ledger.
--     detected_source: upload | fetch | poll (single trigger point — subscriptions
--     reach the queue through fetch). UNIQUE(old_spec_id, new_spec_id) makes
--     re-analysis an upsert: change list refreshes, human disposition survives.
--     status: 0 = pending review, 1 = handled (disposition is a trace only — no case
--     or request state machine is touched, same discipline as F3).
CREATE TABLE IF NOT EXISTS tp_api_spec_change_analysis (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT        NOT NULL,
    old_spec_id     BIGINT        NOT NULL,
    new_spec_id     BIGINT        NOT NULL,
    changes         JSONB         NOT NULL,
    detected_source VARCHAR(20)   NOT NULL DEFAULT 'upload',
    status          SMALLINT      NOT NULL DEFAULT 0,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    handled_by      BIGINT,
    handled_at      TIMESTAMPTZ,
    handle_note     TEXT,
    CONSTRAINT ck_spec_change_analysis_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_spec_change_analysis_source CHECK (detected_source IN ('upload', 'fetch', 'poll')),
    CONSTRAINT uk_spec_change_analysis_pair UNIQUE (old_spec_id, new_spec_id)
);
CREATE INDEX IF NOT EXISTS idx_spec_change_analysis_project
    ON tp_api_spec_change_analysis (project_id, status, id DESC);

COMMENT ON TABLE  tp_api_spec_change_analysis IS 'Contract change-analysis review queue: endpoint diff ledger + closed impact lists + disposition trace';
COMMENT ON COLUMN tp_api_spec_change_analysis.changes           IS 'JSONB [{endpointKey,changeType:add|changed|removed,requestIds[],caseIds[],mockStubIds[]}] — keys only, no snapshot text';
COMMENT ON COLUMN tp_api_spec_change_analysis.detected_source   IS 'upload | fetch | poll (which entry point landed this version)';
COMMENT ON COLUMN tp_api_spec_change_analysis.status            IS '0 = pending review, 1 = handled (trace only, never mutates case/request state machines)';
