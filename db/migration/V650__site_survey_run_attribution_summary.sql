-- Site-survey attribution summary column: the batch-close attribution
-- read-out (facts assembled core-side, explained by the existing insight
-- leg) persists one short paragraph per terminal survey run so write-guard
-- observations and network-level failures are explainable on the report
-- without re-running the survey.
-- Replay-safe: ADD COLUMN IF NOT EXISTS.
ALTER TABLE tp_site_survey_run
    ADD COLUMN IF NOT EXISTS attribution_summary TEXT;
