-- Migration: production dual-review columns on the upgrade plan (AF batch, pack D back half).
-- What this segment lands and why (decision E of the version-awareness design):
--   1) tp_upgrade_plan.production_scope — the plan DECLARES at creation whether it
--      targets production. Strictness is judged from this declared field, not from
--      auto-detecting the environment (local dev boxes have no way to know they are
--      "not prod" — the operator says so and owns the statement);
--   2) tp_upgrade_plan.maintenance_window — the declared maintenance window text
--      (free-form human range, e.g. "2026-10-14 02:00-02:30"); required before a
--      production-declared plan can leave PREVIEW while
--      release.production.dual-review = 1;
--   3) tp_upgrade_plan.reviewed_by / reviewed_at — the second-person review record
--      (approval-flow pattern reused from the reservation gate, no new scheduler or
--      state added to the machine: the gate consumes these columns at start()).
--   The dual-review switch key itself already landed in V225
--   (release.production.dual-review, default 0 = permissive) — this file adds NO
--   tp_sys_config row, so the config snapshot needs no rebuild (pit 21 N/A).
-- Defaults keep every existing row honest: no old plan claims production scope,
-- so the new gate is a zero-behaviour change for historical plans.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

ALTER TABLE tp_upgrade_plan ADD COLUMN IF NOT EXISTS production_scope BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tp_upgrade_plan ADD COLUMN IF NOT EXISTS maintenance_window VARCHAR(120);
ALTER TABLE tp_upgrade_plan ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(50);
ALTER TABLE tp_upgrade_plan ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;

COMMENT ON COLUMN tp_upgrade_plan.production_scope IS
    'Plan declares it targets production (creation-time statement, judged at start instead of guessing the environment; dual-review gate only strict for TRUE rows while release.production.dual-review=1)';
COMMENT ON COLUMN tp_upgrade_plan.maintenance_window IS
    'Declared maintenance window (human-readable range; production-declared plans must carry it before RUNNING when dual review is strict)';
COMMENT ON COLUMN tp_upgrade_plan.reviewed_by IS
    'Second-person reviewer (username; never the operator themself — that is the whole point of dual review; start() refuses while NULL under strict mode)';
COMMENT ON COLUMN tp_upgrade_plan.reviewed_at IS
    'When the review was recorded (audit evidence, approval-flow pattern reused from the reservation gate)';
