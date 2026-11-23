-- Migration V284: scheduled periodic perf testing columns on tp_perf_scenario (B-8).
-- Nightly low-peak capacity regressions should run themselves: a scenario can pin a cron
-- trigger, an optional override environment, and an enable switch, and the existing
-- minute scanner (scheduleTriggerScan — no new job name, a third candidate family on the
-- same idempotent tp_schedule_fire ledger) launches it through the exact same
-- launch gate spectrum as the manual and CI doors (safety gate, duration ceiling,
-- GA-2 environment lock — zero bypass).
--   schedule_cron      Spring 6-segment cron (validated on the save side; NULL = not scheduled)
--   schedule_enabled   1 = scheduled firing on, 0 = off (default 0 = zero behaviour change for
--                      every existing scenario)
--   schedule_env_id    launch-time environment override for the scheduled door (GB-1 pinned
--                      semantics; NULL = press the scenario default env)
-- Idempotent re-runnable: IF NOT EXISTS guards everywhere.
-- Usage: docker cp db/migration/V284__perf_scenario_schedule_columns.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V284__perf_scenario_schedule_columns.sql

ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS schedule_cron VARCHAR(100);
ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS schedule_enabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS schedule_env_id BIGINT REFERENCES tp_project_env (id);

COMMENT ON COLUMN tp_perf_scenario.schedule_cron IS 'Scheduled firing cron (Spring 6-segment: sec min hour day month weekday; NULL = not scheduled; validated fail-closed on save)';
COMMENT ON COLUMN tp_perf_scenario.schedule_enabled IS 'Scheduled firing switch: 1 = on, 0 = off (default 0 = zero behaviour change for existing scenarios)';
COMMENT ON COLUMN tp_perf_scenario.schedule_env_id IS 'Pinned launch-time environment for scheduled firing (null = press the scenario default env; same GB-1 override resolution as interactive launches)';

CREATE INDEX IF NOT EXISTS ix_perf_scenario_schedule ON tp_perf_scenario (schedule_enabled)
    WHERE schedule_enabled = 1;
