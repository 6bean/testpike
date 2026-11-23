-- Whole-site survey runs freeze their site rules at dispatch time.
--
-- The profile row is editable while a run is in flight; the executor and the
-- step relay must never see a mid-run rewrite. One column on the run ledger
-- holds the merged snapshot (profile rules + install-time hot keys), captured
-- once when the dispatch creates the session and the run row together.

ALTER TABLE tp_site_survey_run
    ADD COLUMN IF NOT EXISTS rules_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN tp_site_survey_run.rules_snapshot IS
    'Frozen whole-site rules (start URL, scope, exclude globs, ignored params, verb guard, budgets, login state name, production guard) captured at dispatch time; consumers read this column instead of the live profile row.';
