-- Release candidate data model (AF batch two-state model: detection lands in the
-- candidate table, publishing stays a human-authorised act).
-- tp_release_candidate = awareness surface: versions detected by polling/push/offline
-- import first land here; only after manual confirmation they are written to the
-- authoritative table tp_release_manifest via ReleaseManifestService.register.
-- Version-unique row = re-detection upserts the detection frame (payload/detected_at);
-- status is monotonic (confirmed/dismissed/replaced rows never roll back on
-- re-detection — no stale banners, no re-notify after publishing).
-- The authoritative source enum stays manual/import unchanged (ruling #5, 2026-10-11).
CREATE TABLE IF NOT EXISTS tp_release_candidate (
    id                    BIGSERIAL    PRIMARY KEY,
    version               VARCHAR(60)  NOT NULL,
    channel               VARCHAR(20)  NOT NULL DEFAULT 'released',
    detected_source       VARCHAR(20)  NOT NULL DEFAULT 'poll',
    detected_at           TIMESTAMP    NOT NULL DEFAULT now(),
    payload               JSONB        NOT NULL DEFAULT '{}',
    status                VARCHAR(20)  NOT NULL DEFAULT 'detected',
    confirmed_manifest_id BIGINT,
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_release_candidate_version UNIQUE (version),
    CONSTRAINT ck_release_candidate_channel CHECK (channel IN ('released', 'prerelease')),
    CONSTRAINT ck_release_candidate_source CHECK (detected_source IN ('poll', 'webhook', 'import')),
    CONSTRAINT ck_release_candidate_status CHECK (status IN ('detected', 'confirmed', 'dismissed', 'replaced')),
    CONSTRAINT ck_release_candidate_payload CHECK (jsonb_typeof(payload) = 'object')
);

COMMENT ON TABLE tp_release_candidate IS 'Release candidate (awareness surface; detected != published — confirmation writes the authoritative manifest and stamps confirmed)';
COMMENT ON COLUMN tp_release_candidate.version IS 'Detected release version (unique row; re-detection overwrites the detection frame)';
COMMENT ON COLUMN tp_release_candidate.channel IS 'Release channel: released = stable, prerelease = pre-release';
COMMENT ON COLUMN tp_release_candidate.detected_source IS 'How it was detected: poll = scheduled polling, webhook = push, import = offline import (authoritative source enum stays unextended; only this table records the fine-grained origin)';
COMMENT ON COLUMN tp_release_candidate.detected_at IS 'Latest detection time (refreshed on every re-detection)';
COMMENT ON COLUMN tp_release_candidate.payload IS 'Raw detection frame as jsonb (release_name/release_notes_md/assets — fill only what the feed actually provides, missing fields are completed in the confirm form; never fabricate the matrix. Dismissal reasons are also kept here)';
COMMENT ON COLUMN tp_release_candidate.status IS 'Status spectrum detected -> confirmed/dismissed/replaced, monotonic (re-detection never reopens a disposed row)';
COMMENT ON COLUMN tp_release_candidate.confirmed_manifest_id IS 'Authoritative manifest row id stamped on confirmation (candidate -> manifest is only ever written through ReleaseManifestService.register)';
