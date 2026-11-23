-- Perf scenario isolation-mode column: production-bound scenarios must carry a declared
-- isolation mode (shadow table / namespace account / header mark). Before this migration the
-- declaration only lived in the wizard's precheck call and was never persisted, so a launch
-- that skipped the wizard carried no isolation truth into the gate or the pressure snapshot.
ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS isolation_mode VARCHAR(20);

ALTER TABLE tp_perf_scenario DROP CONSTRAINT IF EXISTS ck_perf_scenario_isolation_mode;
ALTER TABLE tp_perf_scenario ADD CONSTRAINT ck_perf_scenario_isolation_mode
    CHECK (isolation_mode IS NULL OR isolation_mode IN ('SHADOW_TABLE', 'NS_ACCOUNT', 'HEADER_MARK'));

COMMENT ON COLUMN tp_perf_scenario.isolation_mode IS 'Declared isolation mode (SHADOW_TABLE/NS_ACCOUNT/HEADER_MARK); NULL = not declared, only legal for non-production scenarios; the production launch gate rejects a missing declaration';
