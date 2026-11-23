-- Migration: scale contract — seed the scenario step ceiling key perf.scenario.max-steps.
-- The batch import path (whole collection -> perf steps) can produce hundreds of steps in one
-- save; the save chain rejects a scenario above this ceiling in plain words naming both the
-- current step count and the ceiling. Semantics: default 500 = the worst-case size the wizard
-- windowed step table and the engine script compile are designed for; values below 1, dirty or
-- missing rows fall back to 500 (a mistyped value can never switch the ceiling off).
-- Hot-updatable through the system-config page (K8 discipline, no hardcoded caps at call sites).
-- Idempotent: ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V281__perf_scenario_max_steps_key.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V281__perf_scenario_max_steps_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.scenario.max-steps', '500', '系统', 'int', 0,
     '压测场景步骤数上限（默认 500，区间 1 以上，非法回退 500；批量引入整接口集时超限保存会被拒绝并点名当前步数与上限；热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
