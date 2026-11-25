-- Adds the contract-staleness flag to mock rules.
-- When a newer version of the source contract drops an endpoint, the rule that
-- served it is disabled and flagged instead of deleted: hit accounting and the
-- traffic plane keep their provenance, and the UI can point the user at the
-- fix path from the badge.
ALTER TABLE tp_mock_stub ADD COLUMN IF NOT EXISTS contract_stale BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN tp_mock_stub.contract_stale IS 'True when the source contract no longer declares this endpoint; the rule is disabled and flagged instead of deleted so traffic history stays attributable.';
