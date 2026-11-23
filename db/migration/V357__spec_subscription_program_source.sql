-- Migration V357: contract subscription gains the set-internal reference source .
-- A tp_spec_subscription row now carries source_kind: 'url' = external http(s) pull
-- (V131 original semantics) or 'program_spec' = consumer project subscribes the LATEST
-- version of a provider project's spec inside a shared program (project set lens, V340).
-- url becomes nullable; provider pair columns populate only program_spec rows; CHECK
-- constraints keep the url/pair invariant in the schema itself.
-- Flyway not enabled: apply manually to dev db.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V357__spec_subscription_program_source.sql

ALTER TABLE tp_spec_subscription ALTER COLUMN url DROP NOT NULL;
ALTER TABLE tp_spec_subscription ADD COLUMN IF NOT EXISTS source_kind VARCHAR(16) NOT NULL DEFAULT 'url';
ALTER TABLE tp_spec_subscription ADD COLUMN IF NOT EXISTS provider_project_id BIGINT;
ALTER TABLE tp_spec_subscription ADD COLUMN IF NOT EXISTS provider_spec_name VARCHAR(200);

ALTER TABLE tp_spec_subscription DROP CONSTRAINT IF EXISTS ck_spec_subscription_source_kind;
ALTER TABLE tp_spec_subscription ADD CONSTRAINT ck_spec_subscription_source_kind
    CHECK (source_kind IN ('url', 'program_spec'));
ALTER TABLE tp_spec_subscription DROP CONSTRAINT IF EXISTS ck_spec_subscription_source_fields;
ALTER TABLE tp_spec_subscription ADD CONSTRAINT ck_spec_subscription_source_fields
    CHECK ((source_kind = 'url' AND url IS NOT NULL)
        OR (source_kind = 'program_spec' AND provider_project_id IS NOT NULL
            AND provider_spec_name IS NOT NULL));

COMMENT ON COLUMN tp_spec_subscription.source_kind IS 'Pull source: url = external http(s) fetch (V131), program_spec = in-program provider contract reference (T287)';
COMMENT ON COLUMN tp_spec_subscription.provider_project_id IS 'Provider project id for program_spec rows; set-membership fence validated in the service single source';
COMMENT ON COLUMN tp_spec_subscription.provider_spec_name IS 'Provider contract name whose latest version row is the pull target';

-- The change-analysis ledger records which entry point landed a version (V253
-- detected_source). The set-internal subscription pull is a new entry point tag
-- 'program', so the allowed set widens to upload|fetch|poll|program.
ALTER TABLE tp_api_spec_change_analysis DROP CONSTRAINT IF EXISTS ck_spec_change_analysis_source;
ALTER TABLE tp_api_spec_change_analysis ADD CONSTRAINT ck_spec_change_analysis_source
    CHECK (detected_source IN ('upload', 'fetch', 'poll', 'program'));
COMMENT ON COLUMN tp_api_spec_change_analysis.detected_source IS 'upload | fetch | poll | program (which entry point landed this version; program = set-internal subscription pull, T287)';
