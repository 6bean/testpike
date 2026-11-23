-- Migration: B-2 — widen the pressure-model check constraint from the original
-- three shapes (constant/ramp/spike) to seven: FIXED_VU (closed user pool with think
-- time), LADDER (stepped rate search for capacity ceiling), BUSINESS_CURVE (hourly
-- weight profile replaying a real traffic day) and SOAK (long-duration stability).
-- The save-gate single source is PerfScenarioServiceImpl.PRESSURE_MODELS; this
-- constraint mirrors the same set (defense-in-depth for direct SQL writes, never a
-- second dialect — extend both sides together whenever a model is added).
-- Idempotent: drop-if-exists then recreate.
-- Usage: docker cp db/migration/V279__perf_pressure_models_b2.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V279__perf_pressure_models_b2.sql
ALTER TABLE tp_perf_scenario
    DROP CONSTRAINT IF EXISTS ck_perf_scenario_model;

ALTER TABLE tp_perf_scenario
    ADD CONSTRAINT ck_perf_scenario_model CHECK (pressure_model IN (
        'CONSTANT_RPS', 'RAMP', 'SPIKE',
        'FIXED_VU', 'LADDER', 'BUSINESS_CURVE', 'SOAK'));
