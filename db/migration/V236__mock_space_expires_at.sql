-- Migration: mock space expiry column .
-- expires_at is set when a CI door creates a throwaway space with a TTL:
-- short-lived pipeline sandboxes must not leak into the routing plane
-- forever. NULL = never expires (every UI-created space).
-- The column is NOT traffic content — the snapshot pin ignores it, because
-- expiry changes the space's life span, not what served requests see. The
-- expiry sweep (retire + notify or auto-retire) lands in next to
-- the SchedulerJobCatalog entry.
ALTER TABLE tp_mock_space ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
COMMENT ON COLUMN tp_mock_space.expires_at IS 'CI-created space expiry instant (NULL = never expires); swept by the scheduled expiry job, not a traffic-content field';
