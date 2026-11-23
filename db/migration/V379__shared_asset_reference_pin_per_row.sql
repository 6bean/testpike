-- Migration V379: shared asset pins widen from one pin per host to one pin per asset row.
-- The first reference table allowed a host entity to hold exactly one pin, so pinning a
-- second shared asset silently moved the first one away ("who uses me" rosters and update
-- notifications lost the first asset). A host may now pin several distinct asset chains
-- simultaneously; uniqueness is (asset row, referrer kind, referrer id), so re-pinning the
-- same row is an idempotent replace. A same-chain re-pin (switching to a newer version row)
-- must first retire the host's older pin on that chain, which the service performs inside
-- the same transaction, keeping per-version pin timelines exact.
-- Flyway not enabled: apply manually to each chain database.
BEGIN;
ALTER TABLE tp_asset_reference DROP CONSTRAINT IF EXISTS uq_asset_reference_host;
ALTER TABLE tp_asset_reference
    ADD CONSTRAINT uq_asset_reference_row_per_host UNIQUE (asset_row_id, referrer_kind, referrer_id);
COMMENT ON COLUMN tp_asset_reference.referrer_id IS
    'Host entity row id inside the consumer project; resolved by the consuming domain, no cross-table FK. A host may pin several asset chains; uniqueness is per (asset row, host) so a same-chain re-pin replaces the pinned version row';
COMMIT;
