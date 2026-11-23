-- W-d third engine (JMeter): widen the scenario engine CHECK to accept JMETER.
-- Same posture as V289 (drop + re-add, NULL stays allowed = engine optional per scenario).
ALTER TABLE tp_perf_scenario DROP CONSTRAINT IF EXISTS ck_perf_scenario_engine;
ALTER TABLE tp_perf_scenario ADD CONSTRAINT ck_perf_scenario_engine
    CHECK (engine IS NULL OR engine IN ('K6', 'GATLING', 'JMETER'));
