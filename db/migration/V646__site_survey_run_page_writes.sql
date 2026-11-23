-- Whole-site survey runs gain a per-page write tally used by the re-survey
-- diff proposal (new / vanished / changed pages between consecutive runs of
-- one profile). Keys are folded page keys, values are candidate counts
-- written to the element ledger for that page in this run.
ALTER TABLE tp_site_survey_run
    ADD COLUMN IF NOT EXISTS page_writes JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN tp_site_survey_run.page_writes IS
    'Per-page candidate writes of this survey run, keyed by folded page path ({"<pageKey>": n}); consumed by the re-survey review proposal.';
