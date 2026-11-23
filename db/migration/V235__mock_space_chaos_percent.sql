-- Migration: mock space chaos dial column .
-- chaos_percent is the space-level fault-injection dial: 0-100 percent of
-- served requests the traffic plane answers with a plain 503 (retry-storm /
-- frontend resilience drill). It is TRAFFIC CONTENT — the snapshot pin folds
-- it in, so moving the dial swaps the loader's served generation instead of
-- drifting silently under live traffic mid-pin.
-- The range is guarded twice on purpose: the save-chain validator gives the
-- plain-word refusal, this CHECK is the last honest word at the row level
-- against any side-door write.
ALTER TABLE tp_mock_space ADD COLUMN IF NOT EXISTS chaos_percent SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_mock_space DROP CONSTRAINT IF EXISTS ck_mock_space_chaos_percent;
ALTER TABLE tp_mock_space ADD CONSTRAINT ck_mock_space_chaos_percent CHECK (chaos_percent BETWEEN 0 AND 100);
COMMENT ON COLUMN tp_mock_space.chaos_percent IS 'Space-level chaos dial 0-100 (percent of served requests answered 503 by the fault kernel); traffic content - folded into the snapshot pin';
