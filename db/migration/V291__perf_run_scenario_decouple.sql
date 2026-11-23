-- Migration: keep load-test history when a scenario is deleted (A-13, D2 ruling).
-- Purpose: a scenario row is the editable recipe, not the owner of its past runs.
--   Deleting the recipe must not cascade-delete measured history, so the run->scenario
--   link becomes optional: historical runs stay with a NULL link (decoupled), while
--   every display face reads names and parameters from the pinned launch snapshot.
-- Semantics:
--   tp_perf_run.scenario_id   NULL = the scenario was deleted after this run finished;
--                             the run itself (status, snapshot, stop reason, mark) is
--                             untouched, and reports degrade honestly (driver_limit is
--                             a live scenario read, absent shows nothing — never a
--                             fabricated value).
--   Live runs (status=1) can never carry a NULL link: the delete door refuses while
--   any run is in flight, so the environment-exclusion guard join stays total.
ALTER TABLE tp_perf_run ALTER COLUMN scenario_id DROP NOT NULL;

COMMENT ON COLUMN tp_perf_run.scenario_id IS 'Owning scenario recipe; NULL after the scenario was deleted (A-13 keeps finished runs, history reads the pinned snapshot).';
