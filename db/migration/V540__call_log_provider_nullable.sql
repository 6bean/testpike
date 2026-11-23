-- tp_ai_call_log.provider: drop the NOT NULL constraint. A provider call that
-- fails before provider resolution physically has no supplier identity; that
-- absence is an honest ledger state (same convention as tokens = 0 for an
-- unknown cost), not a schema violation. Failed extraction-plane rows must
-- land with provider = NULL so the per-generation burn audit sees them
-- (field evidence 2026-11-17, adjudication doc docs/change-eval/
-- model/function keep NOT NULL: every seam call always carries both.
ALTER TABLE tp_ai_call_log ALTER COLUMN provider DROP NOT NULL;
