-- Migration: mount B-9 custom load-test scripts on the scenario model.
-- Purpose: a scenario declares one thing-to-run — either the step DSL (source=DSL,
--   the historical default) or a user-authored engine script kept in object storage
--   (source=CUSTOM + engine binding + object key of the body). The script body
--   never lives in a column: same posture as the CSV dataset pool (metadata in the
--   row, bytes in storage).
-- Semantics:
--   source            'DSL' (default, every existing row backfilled by DEFAULT) or
--                     'CUSTOM'; CHECK keeps the domain honest at the DB edge too.
--   engine            only meaningful for CUSTOM (K6/GATLING — value domain is
--                     PerfRunDispatchSupport.ENGINE_VALUES, judged at save time);
--                     NULL for DSL scenarios.
--   custom_script_key object-storage key of the script body (perf-script scene,
--                     project-routed like perf-dataset); NULL for DSL scenarios.
-- Backfill: nothing to compute — DEFAULT 'DSL' covers all existing rows, and the
--   DSL save chain keeps writing source explicitly so the two paths can never drift.
ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS source VARCHAR(10) NOT NULL DEFAULT 'DSL';
ALTER TABLE tp_perf_scenario DROP CONSTRAINT IF EXISTS ck_perf_scenario_source;
ALTER TABLE tp_perf_scenario ADD CONSTRAINT ck_perf_scenario_source
    CHECK (source IN ('DSL', 'CUSTOM'));

ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS engine VARCHAR(20);
ALTER TABLE tp_perf_scenario DROP CONSTRAINT IF EXISTS ck_perf_scenario_engine;
ALTER TABLE tp_perf_scenario ADD CONSTRAINT ck_perf_scenario_engine
    CHECK (engine IS NULL OR engine IN ('K6', 'GATLING'));

ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS custom_script_key VARCHAR(500);

COMMENT ON COLUMN tp_perf_scenario.source IS 'Scenario kind: DSL step orchestration (default) or CUSTOM user script mounted from object storage (B-9).';
COMMENT ON COLUMN tp_perf_scenario.engine IS 'Engine binding for CUSTOM scenarios (K6/GATLING); NULL for DSL scenarios.';
COMMENT ON COLUMN tp_perf_scenario.custom_script_key IS 'Object-storage key of the CUSTOM script body (perf-script scene); NULL for DSL scenarios.';
