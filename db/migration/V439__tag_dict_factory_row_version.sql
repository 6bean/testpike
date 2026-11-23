-- V439: optimistic-lock version columns on the tag dictionary and the
-- data-factory script rows (AM batch full edit-anchor
-- coverage). The dialog re-anchors an edit proposal onto the live row
-- version at landing time (V330 server-side anchor switch), which only has
-- teeth when persisted edits bump a version at all; both rows were updated
-- in place with no ledger, so a stale draft could silently overwrite a
-- newer save. DEFAULT 1 keeps every existing row legal without a backfill
-- pass (same posture as tp_mock_stub in V240 / tp_mock_space in V228).
ALTER TABLE tp_tag_dict
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN tp_tag_dict.version IS
    'Optimistic lock column (V439): rename and merge bump it; the dialog draft anchor re-reads it at landing (same posture as tp_mock_stub.version)';

ALTER TABLE tp_data_factory
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN tp_data_factory.version IS
    'Optimistic lock column (V439): every update bumps it; the dialog draft anchor re-reads it at landing (same posture as tp_mock_stub.version)';
