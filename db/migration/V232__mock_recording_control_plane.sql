-- Migration: recording control plane columns .
-- tp_mock_space gains the bound proxy target: start() writes it after the
-- allowlist gate so 's forwarder reads one single source per space
-- (NULL = this space never proxied). tp_mock_recording gains explicit
-- started_at/ended_at stamps: created_at is row-insert noise, the control
-- plane must carry the honest open/seal moments of a cassette box.
-- The (space_id, id DESC) index serves the newest-box-first list page.
-- No new tp_sys_config keys here (mock.proxy.allowlist was seeded by V228).
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

ALTER TABLE tp_mock_space ADD COLUMN IF NOT EXISTS proxy_target_url VARCHAR(300);
COMMENT ON COLUMN tp_mock_space.proxy_target_url IS 'Bound proxy target base URL written by the recording start gate (NULL = never proxied); the proxy forwarder consumes this single source';

ALTER TABLE tp_mock_recording ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE tp_mock_recording ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP;
COMMENT ON COLUMN tp_mock_recording.started_at IS 'Box-open stamp written by the control plane (distinct from created_at row-insert time)';
COMMENT ON COLUMN tp_mock_recording.ended_at IS 'Box-seal stamp written when the recording stops (NULL while open)';

CREATE INDEX IF NOT EXISTS idx_mock_recording_space ON tp_mock_recording (space_id, id DESC);
