-- V240: optimistic-lock column on the mock stub row (serving-inventory +
-- stub edit/delete segment). Editing a live rule races with every other writer
-- (UI, contract import, hit promote, AI confirm), and the space table already
-- froze this posture in V228: a lost race must fail loudly with a version
-- conflict, never silently overwrite. DEFAULT 1 keeps every existing row
-- legal without a backfill pass.
ALTER TABLE tp_mock_stub
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN tp_mock_stub.version IS
    'Optimistic lock column (V240): every update bumps it, a stale expected version is refused with the plain-word conflict (same posture as tp_mock_space.version)';

CREATE INDEX IF NOT EXISTS idx_mock_stub_hit_stub_time ON tp_mock_hit_log (stub_id, hit_at DESC);
